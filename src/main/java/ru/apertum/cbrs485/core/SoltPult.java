package ru.apertum.cbrs485.core;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import ru.apertum.cbrs485.core.protocols.Solt;

/**
 * Приём сигналов с пультов вызова SOLT через модем SB5-MPRT.
 *
 * Пульт по радио шлёт свой BELL ID и клавишу, модем отдаёт это в COM порт тем же пакетом MPRT:
 * STX | keycode (2) | 0x20 | bellid (6) [| 0x20 | display ...] | ETX, например 02 31 34 20 35 42 31 32 43 34 03.
 * По таблице config/solt_pults.csv пульт привязан к окну оператора, а клавиша — к действию в QSystem:
 * 1 - вызвать следующего, 3 - начать приём, 15 - завершить (или "не явился", если приём не начинали).
 *
 * Пакеты с BELL ID, которых нет в таблице, игнорируются: в том числе наши же пакеты для SM7 (AA0000...),
 * если модем вдруг вернёт их обратно.
 */
public class SoltPult {

    public static final File PULTS_FILE = new File("config/solt_pults.csv");

    public enum Action {
        INVITE, START, FINISH
    }

    /**
     * Что делать с распознанным нажатием. В QSystem — QSystemCommands, в тестере — эмуляция.
     */
    public interface Handler {

        void press(String bellId, String key, String point, Action action);
    }

    /**
     * Нажатие, разобранное из пакета модема.
     */
    public static class Press {

        public final String bellId;
        public final String key;
        public final String raw;

        Press(String bellId, String key, String raw) {
            this.bellId = bellId;
            this.key = key;
            this.raw = raw;
        }
    }

    private final Map<String, String> pultToPoint = new HashMap<>();
    private final Map<String, Action> keyToAction = new HashMap<>();
    private final Map<String, Long> lastPress = new HashMap<>();
    private final ByteArrayOutputStream frame = new ByteArrayOutputStream(32);
    private boolean inFrame;
    private long repeatMs = 2000;
    private String serverHost = "127.0.0.1";
    private int serverPort = 3128;
    /** пульт работает, только если оператор его окна вошёл в программу */
    private boolean onlyLogged = true;

    public SoltPult() {
        keyToAction.put("1", Action.INVITE);
        keyToAction.put("3", Action.START);
        keyToAction.put("15", Action.FINISH);
    }

    /**
     * Загрузить таблицу пультов. Формат строк (разделитель ; или ,):
     * BELL ID пульта;окно            - 5B12C4;1
     * key;клавиша;INVITE|START|FINISH - переназначить клавишу
     * server;адрес;порт              - куда слать команды QSystem (по умолчанию 127.0.0.1;3128)
     * repeat;мс                      - повтор того же нажатия в пределах этого времени игнорируется
     */
    public static SoltPult load(File file) {
        final SoltPult p = new SoltPult();
        if (!file.exists()) {
            PortLog.info("PULTS file not found " + file + " - приём с пультов выключен");
            return p;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // BOM, если файл сохранили из Блокнота
                if (!line.isEmpty() && line.charAt(0) == 0xFEFF) {
                    line = line.substring(1);
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String[] c = line.split("[;,]");
                for (int i = 0; i < c.length; i++) {
                    c[i] = c[i].trim();
                }
                try {
                    switch (c[0].toLowerCase()) {
                        case "key":
                            p.keyToAction.put(c[1], Action.valueOf(c[2].toUpperCase()));
                            break;
                        case "server":
                            p.serverHost = c[1];
                            p.serverPort = Integer.parseInt(c[2]);
                            break;
                        case "repeat":
                            p.repeatMs = Long.parseLong(c[1]);
                            break;
                        case "onlylogged":
                            p.onlyLogged = !"false".equalsIgnoreCase(c[1]) && !"0".equals(c[1]);
                            break;
                        default:
                            if (c.length >= 2) {
                                p.pultToPoint.put(c[0].toUpperCase(), c[1]);
                            }
                    }
                } catch (RuntimeException ex) {
                    PortLog.info("PULTS bad line in " + file + ": " + line);
                }
            }
        } catch (IOException ex) {
            PortLog.error("PULTS read " + file, ex);
        }
        PortLog.info("PULTS loaded " + p.pultToPoint.size() + " pults, keys=" + p.keyToAction + " server=" + p.serverHost + ":" + p.serverPort);
        return p;
    }

    public boolean isEmpty() {
        return pultToPoint.isEmpty();
    }

    public int size() {
        return pultToPoint.size();
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public boolean isOnlyLogged() {
        return onlyLogged;
    }

    public String getPoint(String bellId) {
        return pultToPoint.get(bellId.toUpperCase());
    }

    public Action getAction(String key) {
        return keyToAction.get(key);
    }

    /**
     * Скормить байт из порта. Возвращает разобранное нажатие, когда пришёл ETX, иначе null.
     */
    public synchronized Press feed(int b) {
        if (b == Solt.STX) {
            frame.reset();
            inFrame = true;
            return null;
        }
        if (!inFrame) {
            return null;
        }
        if (b == Solt.ETX) {
            inFrame = false;
            return parse(new String(frame.toByteArray(), StandardCharsets.US_ASCII));
        }
        if (frame.size() > 40) {
            // мусор без ETX
            inFrame = false;
            return null;
        }
        frame.write(b);
        return null;
    }

    /**
     * "14 5B12C4" или "14 5B12C4 100 -10" -> клавиша 1, BELL ID 5B12C4.
     */
    static Press parse(String body) {
        final String[] parts = body.trim().split(" +");
        if (parts.length < 2 || !parts[1].matches("[0-9A-Fa-f]{6}")) {
            return null;
        }
        final int code;
        try {
            code = Integer.parseInt(parts[0], 16);
        } catch (NumberFormatException ex) {
            return null;
        }
        final String key = Solt.keyName(code);
        if (key == null) {
            return null;
        }
        return new Press(parts[1].toUpperCase(), key, body);
    }

    /**
     * Пульт шлёт одно нажатие несколькими одинаковыми пакетами — берём только первый.
     */
    public synchronized boolean isRepeat(Press p) {
        final String k = p.bellId + "|" + p.key;
        final long now = System.currentTimeMillis();
        final Long prev = lastPress.put(k, now);
        return prev != null && now - prev < repeatMs;
    }

    /**
     * Полная обработка нажатия: пульт в таблице, клавиша в таблице, не повтор -> handler.
     *
     * @return описание того, что сделали, для лога
     */
    public String dispatch(Press p, Handler handler) {
        final String point = getPoint(p.bellId);
        if (point == null) {
            return "пульт " + p.bellId + " не в " + PULTS_FILE + " - пропуск";
        }
        final Action action = getAction(p.key);
        if (action == null) {
            return "пульт " + p.bellId + " клавиша " + p.key + " без действия - пропуск";
        }
        if (isRepeat(p)) {
            return "пульт " + p.bellId + " клавиша " + p.key + " повтор - пропуск";
        }
        handler.press(p.bellId, p.key, point, action);
        return "пульт " + p.bellId + " клавиша " + p.key + " -> окно " + point + " " + action;
    }
}
