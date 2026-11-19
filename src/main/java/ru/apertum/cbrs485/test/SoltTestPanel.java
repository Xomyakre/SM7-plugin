package ru.apertum.cbrs485.test;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import ru.apertum.cbrs485.core.RS485Property;
import ru.apertum.cbrs485.core.RS485Property.Operator;
import ru.apertum.cbrs485.core.protocols.Solt;
import ru.evgenic.rxtx.serialPort.ISerialPort;

/**
 * Тест SOLT MPRT: модем SB5-MPRT -> табло SM7.
 * Вызов талона идёт тем же путём, что и в QSystem: событие -> Solt -> config/solt_map.csv -> байты -> COM порт.
 * Ручная отправка собирает пакет напрямую из BELL ID и KEY CODE, без таблицы.
 */
public class SoltTestPanel extends JPanel {

    private final Supplier<RS485Property> props;
    private final Pr9800ScenarioPanel.PortOpener portOpener;

    private final JComboBox<String> cbPoint = new JComboBox<>();
    private final JTextField tfTicket = new JTextField("A015", 8);

    private final JTextField tfBell = new JTextField("131976", 8);
    private final JComboBox<String> cbKey = new JComboBox<>(new String[]{"1", "2", "3", "4", "5", "6", "7", "8",
            "9", "10", "11", "12", "13", "14", "15", "16"});
    private final JTextField tfDisplay = new JTextField("", 8);

    private final JSpinner spFrom = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JSpinner spTo = new JSpinner(new SpinnerNumberModel(100, 0, 9999, 1));
    private final JSpinner spDelay = new JSpinner(new SpinnerNumberModel(2, 1, 60, 1));

    private final JTextArea taLog = new JTextArea(15, 60);
    private volatile boolean stopRun;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "solt-test");
        t.setDaemon(true);
        return t;
    });

    public SoltTestPanel(Supplier<RS485Property> props, Pr9800ScenarioPanel.PortOpener portOpener) {
        super(new BorderLayout());
        this.props = props;
        this.portOpener = portOpener;

        // 1. Как в QSystem
        final JButton btnRefresh = new JButton("Обновить окна");
        final JButton btnInvite = new JButton("Вызвать талон");
        final JButton btnFinish = new JButton("Завершить");
        final JButton btnReload = new JButton("Перечитать solt_map.csv");
        btnRefresh.addActionListener(e -> refreshPoints());
        btnInvite.addActionListener(e -> submit(() -> sendInvite(tfTicket.getText().trim())));
        btnFinish.addActionListener(e -> submit(this::sendFinish));
        btnReload.addActionListener(e -> {
            soltProtocol().reloadMap();
            log("Таблица " + Solt.MAP_FILE + " будет перечитана при следующей отправке");
        });

        final JPanel pPlugin = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pPlugin.setBorder(BorderFactory.createTitledBorder("Как в QSystem (через таблицу solt_map.csv)"));
        pPlugin.add(new JLabel("Окно:"));
        pPlugin.add(cbPoint);
        pPlugin.add(btnRefresh);
        pPlugin.add(new JLabel("Талон:"));
        pPlugin.add(tfTicket);
        pPlugin.add(btnInvite);
        pPlugin.add(btnFinish);
        pPlugin.add(btnReload);

        // 2. Вручную
        final JButton btnSendRaw = new JButton("Отправить");
        btnSendRaw.addActionListener(e -> submit(this::sendRaw));

        final JPanel pRaw = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pRaw.setBorder(BorderFactory.createTitledBorder("Вручную (без таблицы)"));
        pRaw.add(new JLabel("BELL ID:"));
        pRaw.add(tfBell);
        pRaw.add(new JLabel("KEY CODE:"));
        pRaw.add(cbKey);
        pRaw.add(new JLabel("Display (пусто = короткий пакет):"));
        pRaw.add(tfDisplay);
        pRaw.add(btnSendRaw);

        // 3. Прогон по всей таблице
        final JButton btnRun = new JButton("Прогнать");
        final JButton btnStop = new JButton("Стоп");
        final JButton btnClosePort = new JButton("Закрыть порт");
        btnClosePort.setToolTipText("Освободить COM порт (например, для COM-Port tester) или применить новые настройки порта");
        btnClosePort.addActionListener(e -> submit(this::closePort));
        btnRun.addActionListener(e -> {
            stopRun = false;
            submit(this::runRange);
        });
        btnStop.addActionListener(e -> stopRun = true);

        final JPanel pRun = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pRun.setBorder(BorderFactory.createTitledBorder("Прогон номеров по таблице"));
        pRun.add(new JLabel("с"));
        pRun.add(spFrom);
        pRun.add(new JLabel("по"));
        pRun.add(spTo);
        pRun.add(new JLabel("пауза, с:"));
        pRun.add(spDelay);
        pRun.add(btnRun);
        pRun.add(btnStop);
        pRun.add(btnClosePort);

        final JPanel top = new JPanel(new GridLayout(3, 1));
        top.add(pPlugin);
        top.add(pRaw);
        top.add(pRun);

        taLog.setEditable(false);
        taLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(taLog), BorderLayout.CENTER);

        refreshPoints();
    }

    private void refreshPoints() {
        cbPoint.removeAllItems();
        final RS485Property rsp = props.get();
        if (rsp == null) {
            cbPoint.addItem("1");
            return;
        }
        for (Operator o : rsp.getOperators()) {
            cbPoint.addItem(o.getPoint());
        }
        if (cbPoint.getItemCount() == 0) {
            cbPoint.addItem("1");
        }
    }

    private Solt soltProtocol() {
        final RS485Property rsp = props.get();
        if (rsp != null && rsp.getProtocols() != null && rsp.getProtocols().getSolt() instanceof Solt) {
            return (Solt) rsp.getProtocols().getSolt();
        }
        if (fallback == null) {
            fallback = new Solt();
        }
        return fallback;
    }
    private Solt fallback;

    /**
     * Последний вызванный BELL ID по окну — для кнопки "Завершить".
     */
    private final java.util.Map<String, String> lastBell = new java.util.HashMap<>();

    /**
     * Вызов талона по таблице solt_map.csv. Собирает тот же пакет, что Solt.getData() для STATE_INVITED,
     * но без классов QSystem (CustomerState тянет библиотеки сервера, которых нет в тестере).
     */
    private void sendInvite(String ticket) {
        final String point = (String) cbPoint.getSelectedItem();
        final Solt solt = soltProtocol();

        log("--> вызов  окно=" + point + "  талон=" + ticket);
        final String[] entry = solt.findEntry(ticket, 0);
        if (entry == null) {
            log("    талона нет в " + Solt.MAP_FILE + " — ничего не отправлено");
            return;
        }
        final String keyCode = entry[1].isEmpty() ? solt.getKeyCode() : entry[1];
        log("    в таблице: BELL ID=" + entry[0] + " KEY=" + keyCode);
        final byte[] bytes;
        try {
            bytes = Solt.buildPacket(keyCode, entry[0], solt.getSendDisplay() ? ticket : null);
        } catch (Exception ex) {
            log("    ОШИБКА: " + ex.getMessage());
            return;
        }
        lastBell.put(point, entry[0]);
        send(bytes);
    }

    /**
     * Завершение: как Solt.getData() для STATE_FINISH — клавиша отмены по последнему BELL ID окна.
     */
    private void sendFinish() {
        final String point = (String) cbPoint.getSelectedItem();
        final Solt solt = soltProtocol();

        log("--> завершить  окно=" + point);
        final String bell = lastBell.remove(point);
        if (!solt.getCancel()) {
            log("    отмена выключена (cancel=false в XML) — ничего не отправлено");
            return;
        }
        if (bell == null) {
            log("    в этом окне не было вызова — нечего гасить");
            return;
        }
        final byte[] bytes;
        try {
            bytes = Solt.buildPacket(solt.getCancelKeyCode(), bell, null);
        } catch (Exception ex) {
            log("    ОШИБКА: " + ex.getMessage());
            return;
        }
        send(bytes);
    }

    private void sendRaw() {
        final String display = tfDisplay.getText().trim();
        log("--> вручную  BELL ID=" + tfBell.getText().trim() + " KEY=" + cbKey.getSelectedItem()
                + (display.isEmpty() ? "" : " display=" + display));
        final byte[] bytes;
        try {
            bytes = Solt.buildPacket((String) cbKey.getSelectedItem(), tfBell.getText(), display.isEmpty() ? null : display);
        } catch (Exception ex) {
            log("    ОШИБКА: " + ex.getMessage());
            return;
        }
        send(bytes);
    }

    private void runRange() {
        final int from = (Integer) spFrom.getValue();
        final int to = (Integer) spTo.getValue();
        final long delay = ((Integer) spDelay.getValue()) * 1000L;
        log("=== Прогон " + from + " .. " + to);
        try {
            for (int n = from; n <= to && !stopRun; n++) {
                sendInvite(String.valueOf(n));
                Thread.sleep(delay);
            }
        } catch (InterruptedException ex) {
            log("Прогон прерван");
        }
        log(stopRun ? "=== Остановлено" : "=== Готово");
    }

    private void send(byte[] bytes) {
        final StringBuilder hex = new StringBuilder("    HEX: ");
        final StringBuilder ascii = new StringBuilder("    ASCII: ");
        for (byte b : bytes) {
            final int c = b & 0xFF;
            hex.append(String.format("%02X ", c));
            ascii.append(c == Solt.STX ? "<STX>" : c == Solt.ETX ? "<ETX>" : String.valueOf((char) c));
        }
        log(hex.toString());
        log(ascii.toString());

        try {
            if (serial == null) {
                openDirect();
            }
            final long t1 = System.currentTimeMillis();
            out.write(bytes);
            // flush() НЕ вызываем: в 64-битной сборке RXTX он уходит в nativeDrain() и роняет JVM
            // (EXCEPTION_ACCESS_VIOLATION в rxtxSerial.dll). write() и так пишет в порт сразу.
            // ждём, пока байты физически уйдут: 10 бит на байт + запас для переходника
            Thread.sleep(bytes.length * 10L * 1000L / Math.max(speed, 1) + 50);
            log("    отправлено " + bytes.length + " байт за " + (System.currentTimeMillis() - t1) + " мс");
        } catch (Throwable ex) {
            logError("    ОШИБКА порта", ex);
            closePort();
        }
    }

    /**
     * Порт открываем сами, как Sender485.DirectPort в плагине: без фонового потока RxtxSerialPort,
     * один раз и держим открытым. Поиск порта в RXTX на Windows долгий, поэтому делаем его один раз.
     */
    private gnu.io.CommPortIdentifier portId;
    private gnu.io.SerialPort serial;
    private java.io.OutputStream out;
    private int speed = 9600;

    private void openDirect() throws Exception {
        final long t0 = System.currentTimeMillis();
        log("    открываю порт (только первый раз)...");
        // берём настройки из полей формы слева
        final ISerialPort cfg = portOpener.open();
        final String name = cfg.getName();
        int bits = 8;
        int stopBits = 1;
        int parity = 0;
        if (cfg instanceof ru.evgenic.rxtx.serialPort.RxtxSerialPort) {
            final ru.evgenic.rxtx.serialPort.RxtxSerialPort rp = (ru.evgenic.rxtx.serialPort.RxtxSerialPort) cfg;
            speed = rp.getSpeed();
            bits = rp.getDataBits();
            stopBits = rp.getStopBits();
            parity = rp.getParity();
        }
        if (portId == null || !portId.getName().equalsIgnoreCase(name)) {
            portId = gnu.io.CommPortIdentifier.getPortIdentifier(name);
        }
        // через reflection: в RXTX open() возвращает CommPort, в NRJavaSerial - RXTXPort
        final Object p = portId.getClass().getMethod("open", String.class, int.class).invoke(portId, "SOLT-test", 2000);
        serial = (gnu.io.SerialPort) p;
        serial.setSerialPortParams(speed, bits, stopBits, parity);
        out = serial.getOutputStream();
        log("    порт " + name + " открыт за " + (System.currentTimeMillis() - t0) + " мс ("
                + speed + " " + bits + " stop=" + stopBits + " parity=" + parity + ")");
    }

    private void closePort() {
        if (serial == null) {
            return;
        }
        try {
            out.close();
        } catch (Throwable ex) {
            logError("    ОШИБКА при закрытии потока", ex);
        }
        try {
            serial.close();
            log("    порт освобождён");
        } catch (Throwable ex) {
            logError("    ОШИБКА при освобождении порта", ex);
        }
        serial = null;
        out = null;
    }

    /**
     * Задачи для фонового потока: любая ошибка (в т.ч. Error) попадает в лог, а не теряется молча.
     */
    private void submit(Runnable task) {
        worker.submit(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                logError("ОШИБКА", ex);
            }
        });
    }

    private void logError(String prefix, Throwable ex) {
        log(prefix + ": " + ex);
        ex.printStackTrace();
        Throwable cause = ex.getCause();
        while (cause != null) {
            log("      причина: " + cause);
            cause = cause.getCause();
        }
    }

    private void log(String s) {
        System.out.println(s);
        SwingUtilities.invokeLater(() -> {
            taLog.append(s + "\n");
            taLog.setCaretPosition(taLog.getDocument().getLength());
        });
    }
}
