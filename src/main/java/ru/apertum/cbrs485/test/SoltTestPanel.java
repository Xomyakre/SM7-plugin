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
import javax.swing.JCheckBox;
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
import ru.apertum.cbrs485.core.SoltPult;
import ru.apertum.cbrs485.core.protocols.Solt;
import ru.evgenic.rxtx.serialPort.ISerialPort;

/**
 * Тест SOLT MPRT: модем SB5-MPRT -> табло SM7.
 * Вызов талона идёт тем же путём, что и в QSystem: событие -> Solt -> config/solt_map.csv -> байты -> COM порт.
 * Ручная отправка собирает пакет напрямую из BELL ID и KEY CODE, без таблицы.
 * Пульты: читаем порт модема и показываем, что пришло с пульта; можно эмулировать QSystem прямо на SM7.
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

    private final JCheckBox chEmulate = new JCheckBox("Эмулировать QSystem на SM7", true);
    private final JSpinner spNext = new JSpinner(new SpinnerNumberModel(1, 0, 9999, 1));
    private final JLabel lbPults = new JLabel("пульты: -");
    private volatile boolean listening;
    private SoltPult pults;

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
        final JButton btnWork = new JButton("Начать приём");
        btnWork.addActionListener(e -> submit(() -> sendWork(tfTicket.getText().trim())));
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
        pPlugin.add(btnWork);
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

        // 4. Пульты
        final JButton btnListen = new JButton("Слушать пульты");
        final JButton btnUnlisten = new JButton("Перестать");
        btnListen.addActionListener(e -> submit(this::startListen));
        btnUnlisten.addActionListener(e -> {
            listening = false;
            log("=== Пульты: прослушивание остановлено");
        });
        chEmulate.setToolTipText("1 - следующий номер зелёным, 3 - он же жёлтым, 15 - погасить. Без QSystem, прямо на SM7");

        final JPanel pPult = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pPult.setBorder(BorderFactory.createTitledBorder("Пульты SOLT (приём с модема, таблица solt_pults.csv)"));
        pPult.add(btnListen);
        pPult.add(btnUnlisten);
        pPult.add(lbPults);
        pPult.add(chEmulate);
        pPult.add(new JLabel("следующий номер:"));
        pPult.add(spNext);

        final JPanel top = new JPanel(new GridLayout(4, 1));
        top.add(pPlugin);
        top.add(pRaw);
        top.add(pRun);
        top.add(pPult);

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
        final String[] entry = solt.findEntry(ticket, 0, point);
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
     * Начало приёма: как Solt.getData() для STATE_WORK — тот же BELL ID, клавиша приёма (по умолчанию 3, жёлтый).
     */
    private void sendWork(String ticket) {
        final String point = (String) cbPoint.getSelectedItem();
        final Solt solt = soltProtocol();

        log("--> начать приём  окно=" + point + "  талон=" + ticket);
        String bell = lastBell.get(point);
        if (bell == null) {
            final String[] entry = solt.findEntry(ticket, 0, point);
            if (entry == null) {
                log("    талона нет в " + Solt.MAP_FILE + " — ничего не отправлено");
                return;
            }
            bell = entry[0];
            lastBell.put(point, bell);
        }
        log("    BELL ID=" + bell + " KEY=" + solt.getWorkKeyCode());
        final byte[] bytes;
        try {
            bytes = Solt.buildPacket(solt.getWorkKeyCode(), bell, solt.getSendDisplay() ? ticket : null);
        } catch (Exception ex) {
            log("    ОШИБКА: " + ex.getMessage());
            return;
        }
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
            final int written = serial.writeBytes(bytes, bytes.length);
            if (written != bytes.length) {
                throw new Exception("в порт записано " + written + " из " + bytes.length + " байт, код ошибки " + serial.getLastErrorCode());
            }
            // ждём, пока байты физически уйдут: 10 бит на байт + запас для переходника
            Thread.sleep(bytes.length * 10L * 1000L / Math.max(speed, 1) + 50);
            log("    отправлено " + bytes.length + " байт за " + (System.currentTimeMillis() - t1) + " мс");
        } catch (Throwable ex) {
            logError("    ОШИБКА порта", ex);
            closePort();
        }
    }

    /**
     * Порт открываем через jSerialComm, один раз, и держим открытым.
     * RXTX (rxtxSerial.dll 64 бит) роняет JVM на flush/available/read, поэтому для работы с портом он больше не используется.
     */
    private com.fazecast.jSerialComm.SerialPort serial;
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
        final com.fazecast.jSerialComm.SerialPort p = com.fazecast.jSerialComm.SerialPort.getCommPort(name);
        // стоп-биты: 1 -> ONE_STOP_BIT, 2 -> TWO_STOP_BITS, 3 (1.5 в RXTX) -> ONE_POINT_FIVE_STOP_BITS
        final int jStop = stopBits == 2 ? com.fazecast.jSerialComm.SerialPort.TWO_STOP_BITS
                : stopBits == 3 ? com.fazecast.jSerialComm.SerialPort.ONE_POINT_FIVE_STOP_BITS
                : com.fazecast.jSerialComm.SerialPort.ONE_STOP_BIT;
        // чётность в RXTX и jSerialComm кодируется одинаково: 0 нет, 1 odd, 2 even, 3 mark, 4 space
        p.setComPortParameters(speed, bits, jStop, parity);
        // read() ждёт данные до 200 мс и возвращает 0, если ничего не пришло
        p.setComPortTimeouts(com.fazecast.jSerialComm.SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 0);
        if (!p.openPort()) {
            throw new Exception("Порт " + name + " не открылся (занят другой программой или не существует), код ошибки "
                    + p.getLastErrorCode());
        }
        serial = p;
        log("    порт " + name + " открыт за " + (System.currentTimeMillis() - t0) + " мс ("
                + speed + " " + bits + " stop=" + stopBits + " parity=" + parity + ")");
    }

    private void closePort() {
        final com.fazecast.jSerialComm.SerialPort p = serial;
        if (p == null) {
            return;
        }
        serial = null;
        try {
            p.closePort();
            log("    порт освобождён");
        } catch (Throwable ex) {
            logError("    ОШИБКА при освобождении порта", ex);
        }
    }

    /**
     * Открыть порт (если ещё не открыт) и в отдельном потоке читать всё, что присылает модем.
     */
    private void startListen() {
        if (listening) {
            log("=== Пульты: уже слушаю");
            return;
        }
        pults = SoltPult.load(SoltPult.PULTS_FILE);
        final int n = pults.size();
        SwingUtilities.invokeLater(() -> lbPults.setText("пульты: " + n));
        try {
            if (serial == null) {
                openDirect();
            }
        } catch (Throwable ex) {
            logError("    ОШИБКА порта", ex);
            closePort();
            return;
        }
        listening = true;
        log("=== Пульты: слушаю порт. Пультов в " + SoltPult.PULTS_FILE + ": " + n
                + (n == 0 ? " (будут видны пакеты, но действий не будет - впиши BELL ID пульта в таблицу)" : ""));
        final Thread reader = new Thread(this::readLoop, "solt-test-rx");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Чтение с таймаутом 200 мс через jSerialComm.
     */
    private void readLoop() {
        final byte[] buf = new byte[64];
        while (listening) {
            try {
                final com.fazecast.jSerialComm.SerialPort p = serial;
                if (p == null) {
                    Thread.sleep(300);
                    continue;
                }
                final int cnt = p.readBytes(buf, buf.length);
                if (cnt < 0) {
                    // порт закрыли или выдернули переходник
                    Thread.sleep(500);
                    continue;
                }
                if (cnt == 0) {
                    continue;
                }
                final StringBuilder hex = new StringBuilder("<-- от модема: ");
                for (int i = 0; i < cnt; i++) {
                    hex.append(String.format("%02X ", buf[i] & 0xFF));
                }
                log(hex.toString());
                for (int i = 0; i < cnt; i++) {
                    final SoltPult.Press press = pults.feed(buf[i] & 0xFF);
                    if (press != null) {
                        log("    пакет [" + press.raw + "]: BELL ID=" + press.bellId + " клавиша " + press.key);
                        log("    " + pults.dispatch(press, (bell, key, point, action) -> submit(() -> emulate(point, action))));
                    }
                }
            } catch (InterruptedException ex) {
                return;
            } catch (Throwable ex) {
                logError("    ОШИБКА чтения порта", ex);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /**
     * Без QSystem: делаем на SM7 то же, что сделал бы сервер после команды пульта.
     */
    private void emulate(String point, SoltPult.Action action) {
        if (!chEmulate.isSelected()) {
            log("    (эмуляция выключена - в QSystem было бы: окно " + point + " " + action + ")");
            return;
        }
        final Solt solt = soltProtocol();
        switch (action) {
            case INVITE: {
                final int num = (Integer) spNext.getValue();
                SwingUtilities.invokeLater(() -> spNext.setValue(num + 1));
                log("--> эмуляция: окно " + point + " вызывает номер " + num + " (зелёный)");
                final String[] entry = solt.findEntry(String.valueOf(num), 0, point);
                if (entry == null) {
                    log("    номера " + num + " нет в " + Solt.MAP_FILE);
                    return;
                }
                lastBell.put(point, entry[0]);
                send(Solt.buildPacket(solt.getKeyCode(), entry[0], null));
                break;
            }
            case START: {
                final String bell = lastBell.get(point);
                if (bell == null) {
                    log("--> эмуляция: окно " + point + " начать приём - но никого не вызывали");
                    return;
                }
                log("--> эмуляция: окно " + point + " начать приём (жёлтый)");
                send(Solt.buildPacket(solt.getWorkKeyCode(), bell, null));
                break;
            }
            case FINISH: {
                final String bell = lastBell.remove(point);
                if (bell == null) {
                    log("--> эмуляция: окно " + point + " завершить - нечего гасить");
                    return;
                }
                log("--> эмуляция: окно " + point + " завершить (погасить)");
                send(Solt.buildPacket(solt.getCancelKeyCode(), bell, null));
                break;
            }
            default:
        }
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
