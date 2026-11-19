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
import ru.apertum.cbrs485.core.Event;
import ru.apertum.cbrs485.core.RS485Property;
import ru.apertum.cbrs485.core.RS485Property.Operator;
import ru.apertum.cbrs485.core.protocols.Solt;
import ru.apertum.qsystem.common.CustomerState;
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
        btnInvite.addActionListener(e -> worker.submit(() -> sendEvent(tfTicket.getText().trim(), CustomerState.STATE_INVITED)));
        btnFinish.addActionListener(e -> worker.submit(() -> sendEvent(tfTicket.getText().trim(), CustomerState.STATE_FINISH)));
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
        btnSendRaw.addActionListener(e -> worker.submit(this::sendRaw));

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
        btnRun.addActionListener(e -> {
            stopRun = false;
            worker.submit(this::runRange);
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

    private void sendEvent(String ticket, CustomerState state) {
        final RS485Property rsp = props.get();
        final String point = (String) cbPoint.getSelectedItem();
        final Operator op = rsp == null || point == null ? null : rsp.getOperatorByPoint(point);
        final Solt solt = soltProtocol();

        log("--> " + state + "  окно=" + point + "  талон=" + ticket);
        final String[] entry = solt.findEntry(ticket, 0);
        if (entry != null) {
            log("    в таблице: BELL ID=" + entry[0] + (entry[1].isEmpty() ? "" : " KEY=" + entry[1]));
        }
        final byte[] bytes;
        try {
            bytes = solt.getData(new Event(point, ticket, "", 0, state), op, rsp);
        } catch (Exception ex) {
            log("    ОШИБКА: " + ex.getMessage());
            return;
        }
        if (bytes == null) {
            log(entry == null
                    ? "    талона нет в " + Solt.MAP_FILE + " — ничего не отправлено"
                    : "    протокол ничего не вернул для " + state + " (отмена выключена: cancel=false)");
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
                sendEvent(String.valueOf(n), CustomerState.STATE_INVITED);
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

        ISerialPort port = null;
        try {
            port = portOpener.open();
            port.send(bytes);
            Thread.sleep(250);
            log("    отправлено");
        } catch (Exception ex) {
            log("    ОШИБКА порта: " + ex);
            System.err.println(ex);
        } finally {
            if (port != null) {
                try {
                    port.free();
                } catch (Exception ex) {
                    System.err.println(ex);
                }
            }
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
