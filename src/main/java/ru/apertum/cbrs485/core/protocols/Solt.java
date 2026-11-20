/*
 * Apertum QSystem — протокол SOLT MPRT (модем SB5-MPRT + табло SM7).
 */
package ru.apertum.cbrs485.core.protocols;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ru.apertum.cbrs485.core.Event;
import ru.apertum.cbrs485.core.RS485Property;

/**
 * Протокол SOLT MPRT. Модем SB5-MPRT по радио передаёт на SM7 пару BELL ID + KEY CODE,
 * а SM7 показывает текст, заранее записанный под этой парой.
 *
 * Формат пакета (ASCII):
 * STX | keycode (2) | 0x20 | bellid (6) | 0x20 | display (3) | 0x20 | display (3) | ETX
 * Пример: keycode 1, bellid AB1278, display "100-10":
 * 02 31 34 20 41 42 31 32 37 38 20 31 30 30 20 2D 31 30 03
 * Короткий пакет без display (проверен на SM7, COM 9600 8N1): keycode 1, bellid 131976:
 * 02 31 34 20 31 33 31 39 37 36 03
 *
 * Соответствие талона и BELL ID берётся из файла config/solt_map.csv (строки: талон;bellid[;keycode]).
 *
 * @author Evgeniy Egorov
 */
public class Solt extends RS485Property.SoltProtocol {

    public static final int STX = 0x02;
    public static final int ETX = 0x03;
    public static final int BLANK = 0x20;

    public static final File MAP_FILE = new File("config/solt_map.csv");

    /**
     * Номер клавиши из таблицы SM7 -> код, который уходит в пакет.
     */
    private static final Map<String, Integer> KEY_CODES = new HashMap<>();

    static {
        final Object[][] table = {
                {"1", 0x14}, {"2", 0x24}, {"3", 0x28}, {"4", 0x34}, {"5", 0x38}, {"6", 0x44},
                {"7", 0x48}, {"8", 0x54}, {"9", 0x58}, {"10", 0x64}, {"11", 0x68}, {"12", 0x74},
                {"13", 0x78}, {"14", 0x84}, {"15", 0xE4}, {"16", 0xF4},
                {"1-1", 0x10}, {"1-2", 0x1C}, {"1-3", 0x8C},
                {"3-1", 0x20}, {"3-2", 0x2C}, {"3-3", 0x9C},
                {"5-1", 0x30}, {"5-2", 0x3C}, {"5-3", 0xAC},
                {"7-1", 0x40}, {"7-2", 0x4C}, {"7-3", 0xBC},
                {"9-1", 0x50}, {"9-2", 0x5C}, {"9-3", 0xCC},
                {"11-1", 0x60}, {"11-2", 0x6C}, {"11-3", 0xDC},
                {"13-1", 0x70}, {"13-2", 0x7C}, {"13-3", 0xEC}
        };
        for (Object[] row : table) {
            KEY_CODES.put((String) row[0], (Integer) row[1]);
        }
    }

    /**
     * Обратное преобразование: код из пакета (0x14) -> номер клавиши из таблицы SM7 ("1"). null, если код неизвестен.
     */
    public static String keyName(int code) {
        for (Map.Entry<String, Integer> e : KEY_CODES.entrySet()) {
            if (e.getValue() == code) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Последний отправленный BELL ID по каждому окну — чтобы было что гасить при завершении.
     */
    private static final ConcurrentHashMap<String, String> LAST_BELL = new ConcurrentHashMap<>();

    private Map<String, String[]> ticketMap;

    @Override
    public byte[] getData(Event event, RS485Property.Operator operator, RS485Property props) {
        System.out.println("ClientboardRS485Plugin Protocol: " + this.getName());

        final String ticket = event.ticket == null ? "" : event.ticket.trim();
        final byte[] packet;
        switch (event.state) {
            case STATE_INVITED:
            case STATE_INVITED_SECONDARY: {
                final String[] entry = findEntry(ticket, event.number);
                if (entry == null) {
                    System.err.println("ClientboardRS485Plugin Solt: нет BELL ID для талона \"" + ticket + "\" в " + MAP_FILE);
                    return null;
                }
                final String keyCode = entry.length > 1 && !entry[1].isEmpty() ? entry[1] : getKeyCode();
                LAST_BELL.put(event.point, entry[0]);
                packet = buildPacket(keyCode, entry[0], getSendDisplay() ? ticket : null);
                break;
            }
            case STATE_WORK:
            case STATE_WORK_SECONDARY: {
                // начало приёма: тот же BELL ID, клавиша 3 — на SM7 номер горит жёлтым
                String bell = LAST_BELL.get(event.point);
                if (bell == null) {
                    final String[] entry = findEntry(ticket, event.number);
                    if (entry == null) {
                        System.err.println("ClientboardRS485Plugin Solt: нет BELL ID для талона \"" + ticket + "\" в " + MAP_FILE);
                        return null;
                    }
                    bell = entry[0];
                    LAST_BELL.put(event.point, bell);
                }
                packet = buildPacket(getWorkKeyCode(), bell, getSendDisplay() ? ticket : null);
                break;
            }
            case STATE_DEAD:
            case STATE_FINISH:
            case STATE_POSTPONED:
            case STATE_REDIRECT: {
                final String bell = LAST_BELL.remove(event.point);
                if (!getCancel() || bell == null) {
                    return null;
                }
                packet = buildPacket(getCancelKeyCode(), bell, null);
                break;
            }
            default:
                return null;
        }
        IProtocol.printBytes(packet);
        return packet;
    }

    /**
     * Собрать пакет MPRT. Если display == null, отправляется короткий пакет без поля display.
     */
    public static byte[] buildPacket(String keyCode, String bellId, String display) {
        final Integer code = KEY_CODES.get(keyCode.trim());
        if (code == null) {
            throw new IllegalArgumentException("Неизвестный KEY CODE SOLT: " + keyCode);
        }
        final String bell = bellId.trim().toUpperCase();
        if (!bell.matches("[0-9A-F]{6}")) {
            throw new IllegalArgumentException("BELL ID должен быть из 6 hex-символов: " + bellId);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(20);
        out.write(STX);
        writeAscii(out, String.format("%02X", code));
        out.write(BLANK);
        writeAscii(out, bell);
        if (display != null) {
            final String disp = fit(display, 6);
            out.write(BLANK);
            writeAscii(out, disp.substring(0, 3));
            out.write(BLANK);
            writeAscii(out, disp.substring(3, 6));
        }
        out.write(ETX);
        return out.toByteArray();
    }

    /**
     * Ищем по талону целиком (A015), потом по номеру из события, потом по цифрам талона (A015 -> 15).
     */
    public String[] findEntry(String ticket, int number) {
        final Map<String, String[]> map = getTicketMap();
        String[] entry = map.get(ticket.toUpperCase());
        if (entry == null && number > 0) {
            entry = map.get(String.valueOf(number));
        }
        if (entry == null) {
            final String digits = ticket.replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                entry = map.get(String.valueOf(Integer.parseInt(digits)));
            }
        }
        return entry;
    }

    /**
     * Перечитать config/solt_map.csv (для тестовой формы, после правки таблицы).
     */
    public synchronized void reloadMap() {
        ticketMap = null;
    }

    private synchronized Map<String, String[]> getTicketMap() {
        if (ticketMap == null) {
            ticketMap = loadMap(MAP_FILE);
        }
        return ticketMap;
    }

    static Map<String, String[]> loadMap(File file) {
        final Map<String, String[]> map = new HashMap<>();
        if (!file.exists()) {
            System.err.println("ClientboardRS485Plugin Solt: файл " + file + " не найден");
            return map;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String[] cols = line.split("[;,]");
                if (cols.length < 2) {
                    continue;
                }
                final String key = cols[0].trim().toUpperCase();
                final String bell = cols[1].trim();
                final String keyCode = cols.length > 2 ? cols[2].trim() : "";
                map.put(key, new String[]{bell, keyCode});
            }
        } catch (IOException ex) {
            System.err.println("ClientboardRS485Plugin Solt: ошибка чтения " + file + ": " + ex);
        }
        System.out.println("ClientboardRS485Plugin Solt: загружено соответствий талонов: " + map.size());
        return map;
    }

    /**
     * Текст прижимается вправо в поле нужной длины; не-ASCII символы заменяются пробелом.
     */
    private static String fit(String text, int len) {
        final StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append(c >= 0x20 && c < 0x7F ? c : ' ');
        }
        String s = sb.toString();
        if (s.length() > len) {
            s = s.substring(s.length() - len);
        }
        while (s.length() < len) {
            s = " " + s;
        }
        return s;
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        final byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }
}
