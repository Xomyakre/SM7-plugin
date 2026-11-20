/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ru.apertum.cbrs485.core;

import com.fazecast.jSerialComm.SerialPort;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.bind.JAXBException;
import ru.apertum.cbrs485.core.RS485Property.Operator;
import ru.apertum.qsystem.common.CustomerState;
import ru.evgenic.rxtx.serialPort.ISerialPort;
import ru.evgenic.rxtx.serialPort.RxtxSerialPort;

/**
 * Отсылка на табло. Один поток, посылки строго по очереди, порт открываем сами:
 * вызов клиента - порт открыт и занят, номер мигает; приём - номер горит;
 * завершение - табло гаснет и, если больше никого не обслуживают, порт закрывается.
 *
 * Если настроены пульты SOLT (config/solt_pults.csv), этот же порт ещё и читаем:
 * модем SB5-MPRT отдаёт в него нажатия пультов, а они превращаются в команды QSystem.
 *
 * @author Evgeniy Egorov
 */
public class Sender485 {

    public static Sender485 getInstance() {
        return Sender485Holder.INSTANCE;
    }

    private static class Sender485Holder {

        private static final Sender485 INSTANCE = new Sender485();
    }

    /**
     * Шлём в порт только из процесса сервера. Программа оператора на том же компьютере
     * грузит этот же плагин и меняет статусы своим устаревшим копиям клиентов,
     * из-за чего на табло всплывали прошлые номера.
     * Для персонального табло у оператора запускать клиента с -Drs485.client=true
     */
    private static final boolean ENABLED = Boolean.getBoolean("rs485.client")
            || String.valueOf(System.getProperty("sun.java.command")).startsWith("ru.apertum.qsystem.Server");

    /**
     * Одинаковое событие для того же окна в пределах этого времени считается дублем:
     * QSystem может дернуть оба метода change() на одну смену статуса.
     */
    private static final long DUPLICATE_WINDOW_MS = 1000;

    /**
     * Закрывать порт, когда никого не обслуживают. Открытие порта в Windows занимает несколько секунд
     * (библиотека перебирает все COM порты), поэтому по умолчанию порт держим открытым,
     * иначе каждый вызов клиента появляется на табло с задержкой. Включить: -Drs485.release=true
     * С пультами SOLT порт не закрываем никогда - иначе не услышим нажатия.
     */
    private static final boolean RELEASE_PORT = Boolean.getBoolean("rs485.release");

    /** окно -> последнее не отосланное событие */
    final private LinkedHashMap<String, Event> pending = new LinkedHashMap<>();
    final private HashMap<String, String> lastKey = new HashMap<>();
    final private HashMap<String, Long> lastTime = new HashMap<>();
    /** имя порта -> окна, где сейчас идет вызов или прием */
    final private HashMap<String, Set<String>> busy = new HashMap<>();
    /** имя порта -> открытый нами порт */
    final private HashMap<String, DirectPort> opened = new HashMap<>();
    final private Thread sndThread;
    final private RS485Property props;
    /** пульты SOLT: кто к какому окну привязан */
    final private SoltPult pults;
    final private QSystemCommands commands;

    private Sender485() {
        try {
            props = RS485Property.unmarshal(new FileInputStream(RS485Property.PROP_FILE));
        } catch (JAXBException | FileNotFoundException ex) {
            throw new RuntimeException(ex);
        }
        pults = SoltPult.load(SoltPult.PULTS_FILE);
        commands = new QSystemCommands(pults.getServerHost(), pults.getServerPort());
        System.out.println("Sender ready (v5, enabled=" + ENABLED + "): default port=" + props.getPortName() + " protocol="
                + props.getProtocols().getDefaultPtotocol() + " pults=" + !pults.isEmpty());
        PortLog.info("START v5 enabled=" + ENABLED + " port=" + props.getPortName() + " protocol=" + props.getProtocols().getDefaultPtotocol()
                + " pults=" + !pults.isEmpty() + " cmd=" + System.getProperty("sun.java.command"));

        // поток для отсыла стартуем один раз и навсегда
        sndThread = new Thread(this::loop, "ClientboardRS485Plugin-sender");
        sndThread.setDaemon(true);
        sndThread.start();
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private boolean keepPortOpen() {
        return !RELEASE_PORT || !pults.isEmpty();
    }

    private void loop() {
        if (ENABLED && keepPortOpen()) {
            warmUp();
        }
        while (!Thread.currentThread().isInterrupted()) {
            final Event event;
            try {
                event = takeNext();
            } catch (InterruptedException ex) {
                break;
            }
            try {
                process(event);
            } catch (Throwable ex) {
                // одно плохое событие не должно убивать поток отсылки
                System.err.println("!!! ERROR !!! ClientboardRS485Plugin: " + ex);
                ex.printStackTrace();
                PortLog.error("send failed " + describe(event), ex);
            }
            if (props.getPause() > 0) {
                try {
                    Thread.sleep(props.getPause());
                } catch (InterruptedException ex) {
                    break;
                }
            }
        }
        for (DirectPort p : opened.values()) {
            p.close();
        }
    }

    /**
     * Открываем порты всех табло сразу при старте, чтобы первый вызов клиента не ждал поиска порта
     * и чтобы сразу слышать пульты.
     */
    private void warmUp() {
        for (Operator operator : props.getOperators()) {
            try {
                final ISerialPort cfg = operator.getPort(props);
                if (!opened.containsKey(cfg.getName())) {
                    final DirectPort port = newPort(cfg);
                    port.openIfNeeded();
                    opened.put(cfg.getName(), port);
                }
            } catch (Throwable ex) {
                PortLog.error("warm up failed for window " + operator.getPoint(), ex);
            }
        }
    }

    private DirectPort newPort(ISerialPort cfg) {
        return new DirectPort(cfg, pults.isEmpty() ? null : this::received);
    }

    /**
     * Байты от модема: собираем пакеты MPRT и отдаём нажатия пультов в QSystem.
     */
    private void received(String portName, byte[] bytes) {
        PortLog.info("RX " + portName + " " + PortLog.hex(bytes));
        for (byte b : bytes) {
            final SoltPult.Press press = pults.feed(b & 0xFF);
            if (press != null) {
                final String what = pults.dispatch(press, commands);
                System.out.println("SOLT PULT [" + press.raw + "]: " + what);
                PortLog.info("PULT [" + press.raw + "] " + what);
            }
        }
    }

    private Event takeNext() throws InterruptedException {
        synchronized (pending) {
            while (pending.isEmpty()) {
                pending.wait();
            }
            final Iterator<Map.Entry<String, Event>> it = pending.entrySet().iterator();
            final Event event = it.next().getValue();
            it.remove();
            return event;
        }
    }

    private static boolean isClear(CustomerState state) {
        return state == CustomerState.STATE_FINISH
                || state == CustomerState.STATE_DEAD
                || state == CustomerState.STATE_POSTPONED
                || state == CustomerState.STATE_REDIRECT;
    }

    private void process(Event event) throws Exception {
        final Operator operator = props.getOperatorByPoint(event.point);
        if (operator == null) {
            System.out.println("!!!!!!!!!!!!!  " + event.point + " NOT FOUND");
            PortLog.info("SKIP window not in settings " + describe(event));
            return;
        }
        final byte[] bytes = operator.getNProtocol(props).getData(event, operator, props);
        if (bytes == null) {
            // протоколу нечего показывать для такого состояния
            PortLog.info("SKIP protocol returned nothing " + describe(event));
            return;
        }

        // настройки порта (имя, скорость, биты) берем из того же объекта, что и раньше, но сам порт открываем сами
        final ISerialPort cfg = operator.getPort(props);
        final String portName = cfg.getName();
        final Set<String> windows = busy.computeIfAbsent(portName, k -> new HashSet<>());
        if (isClear(event.state)) {
            windows.remove(event.point);
        } else {
            windows.add(event.point);
        }

        DirectPort port = opened.get(portName);
        if (port == null) {
            port = newPort(cfg);
            opened.put(portName, port);
        }

        System.out.println("SEND TO RS485 [" + event.point + " " + event.state + " '" + event.ticket + "'] " + PortLog.hex(bytes));
        PortLog.info("SEND " + describe(event) + " addr=" + operator.getAddressRS());
        try {
            port.write(bytes);
        } catch (Exception ex) {
            // порт мог отвалиться (выдернули переходник) - переоткрываем и пробуем еще раз
            PortLog.error("write failed, reopen " + portName, ex);
            port.close();
            port.write(bytes);
        }

        if (windows.isEmpty() && !keepPortOpen()) {
            // никого не обслуживают - освобождаем порт до следующего вызова
            port.close();
        }
    }

    private static String describe(Event event) {
        return "[window=" + event.point + " state=" + event.state + " ticket='" + event.ticket + "']";
    }

    public void send(Event event) {
        if (event == null) {
            return;
        }
        if (!ENABLED) {
            PortLog.info("IGNORED not a server process " + describe(event));
            return;
        }
        if (event.point == null || event.point.trim().isEmpty()) {
            // у клиента еще нет окна, например он только встал в очередь
            return;
        }
        if (isDuplicate(event)) {
            PortLog.info("DROP duplicate " + describe(event));
            return;
        }
        synchronized (pending) {
            // старое не отосланное событие этого окна больше не нужно, его заменяет новое
            final Event old = pending.remove(event.point);
            if (old != null) {
                PortLog.info("DROP outdated " + describe(old) + " replaced by " + describe(event));
            }
            pending.put(event.point, event);
            pending.notifyAll();
        }
        PortLog.info("QUEUE " + describe(event));
    }

    private synchronized boolean isDuplicate(Event event) {
        final String key = event.state + "|" + event.ticket;
        final long now = System.currentTimeMillis();
        final String prevKey = lastKey.put(event.point, key);
        final Long prevTime = lastTime.put(event.point, now);
        return key.equals(prevKey) && prevTime != null && now - prevTime < DUPLICATE_WINDOW_MS;
    }

    /**
     * Кому отдавать принятые из порта байты.
     */
    interface RxListener {

        void received(String portName, byte[] bytes);
    }

    /**
     * COM порт через jSerialComm. RXTX (rxtxSerial.dll 64 бит) роняет JVM на flush/available/read,
     * поэтому от него берём только настройки порта из XML, а пишем и читаем через jSerialComm.
     * write() возвращается, когда байты уже ушли в провод.
     * Если задан listener, отдельный поток читает порт с таймаутом 200 мс.
     */
    private static class DirectPort {

        private final String name;
        private final int speed;
        private final int bits;
        private final int stopBits;
        private final int parity;
        private final RxListener listener;
        private volatile SerialPort port;

        DirectPort(ISerialPort cfg, RxListener listener) {
            this.name = cfg.getName();
            this.listener = listener;
            if (cfg instanceof RxtxSerialPort) {
                final RxtxSerialPort rp = (RxtxSerialPort) cfg;
                speed = rp.getSpeed();
                bits = rp.getDataBits();
                stopBits = rp.getStopBits();
                parity = rp.getParity();
            } else {
                speed = 9600;
                bits = 8;
                stopBits = 1;
                parity = 0;
            }
            if (listener != null) {
                final Thread reader = new Thread(this::readLoop, "ClientboardRS485Plugin-rx-" + name);
                reader.setDaemon(true);
                reader.start();
            }
        }

        synchronized void openIfNeeded() throws Exception {
            if (port == null) {
                open();
            }
        }

        void write(byte[] bytes) throws Exception {
            openIfNeeded();
            final int written = port.writeBytes(bytes, bytes.length);
            if (written != bytes.length) {
                throw new Exception("written " + written + " of " + bytes.length + " bytes to " + name + ", error " + port.getLastErrorCode());
            }
            // ждем, пока байты физически уйдут: 10 бит на байт + запас для переходника RS485
            Thread.sleep(bytes.length * 10L * 1000L / Math.max(speed, 1) + 50);
            PortLog.tx(name, bytes);
        }

        private void open() throws Exception {
            PortLog.info("OPEN " + name + " speed=" + speed + " bits=" + bits + " stop=" + stopBits + " parity=" + parity);
            final long start = System.currentTimeMillis();
            final SerialPort p = SerialPort.getCommPort(name);
            // стоп-биты: 1 -> ONE_STOP_BIT, 2 -> TWO_STOP_BITS, 3 (1.5 в RXTX) -> ONE_POINT_FIVE_STOP_BITS
            final int jStop = stopBits == 2 ? SerialPort.TWO_STOP_BITS
                    : stopBits == 3 ? SerialPort.ONE_POINT_FIVE_STOP_BITS
                    : SerialPort.ONE_STOP_BIT;
            // чётность в RXTX и jSerialComm кодируется одинаково: 0 нет, 1 odd, 2 even, 3 mark, 4 space
            p.setComPortParameters(speed, bits, jStop, parity);
            // readBytes() ждёт данные до 200 мс и возвращает 0, если ничего не пришло
            p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 0);
            if (!p.openPort()) {
                throw new Exception("port " + name + " not opened (busy or not exists), error " + p.getLastErrorCode());
            }
            port = p;
            PortLog.info("OPENED " + name + " in " + (System.currentTimeMillis() - start) + " ms");
        }

        private void readLoop() {
            final byte[] buf = new byte[64];
            while (true) {
                try {
                    final SerialPort p = port;
                    if (p == null) {
                        Thread.sleep(500);
                        continue;
                    }
                    final int n = p.readBytes(buf, buf.length);
                    if (n > 0) {
                        listener.received(name, Arrays.copyOf(buf, n));
                    } else if (n < 0) {
                        // порт закрыли или выдернули переходник - ждем, пока его снова откроют
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException ex) {
                    return;
                } catch (Throwable ex) {
                    PortLog.error("read " + name, ex);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }

        synchronized void close() {
            final SerialPort p = port;
            if (p == null) {
                return;
            }
            port = null;
            try {
                p.closePort();
            } catch (Exception ex) {
                PortLog.error("close port " + name, ex);
            }
            PortLog.info("CLOSED " + name);
        }
    }
}
