package ru.apertum.cbrs485.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Команды серверу QSystem от пульта: вызвать следующего, начать приём, завершить.
 *
 * Шлём их так же, как программа оператора: NetCommander.send(INetProperty, команда, CmdParams) на адрес сервера.
 * Всё через reflection, чтобы не зависеть от сигнатур конкретной версии QSystem.jar.
 * После команды сервер сам меняет статус клиента, срабатывает EventSender и номер уходит на SM7.
 */
public class QSystemCommands implements SoltPult.Handler {

    /** версия логики пультов - видно в консоли сервера, что стоит новый jar */
    public static final String VERSION = "pult-v3";

    private final String host;
    private final int port;
    private final boolean onlyLogged;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "ClientboardRS485Plugin-pult");
        t.setDaemon(true);
        return t;
    });

    public QSystemCommands(String host, int port, boolean onlyLogged) {
        this.host = host;
        this.port = port;
        this.onlyLogged = onlyLogged;
        log(VERSION + " ready: server=" + host + ":" + port + " onlyLogged=" + onlyLogged);
    }

    @Override
    public void press(String bellId, String key, String point, SoltPult.Action action) {
        // не в потоке чтения порта: ответ сервера может занять время
        worker.submit(() -> execute(point, action));
    }

    private void execute(String point, SoltPult.Action action) {
        try {
            final Long userId = findUserId(point);
            if (userId == null) {
                log("окно " + point + ": оператор с таким номером окна не найден в QSystem");
                return;
            }
            if (onlyLogged) {
                final Boolean logged = isLogged(userId);
                if (Boolean.FALSE.equals(logged)) {
                    log("окно " + point + " (user id " + userId + "): оператор не вошёл в программу - нажатие пульта пропущено");
                    return;
                }
            }
            final String state = customerState(userId);
            switch (action) {
                case INVITE:
                    if (state != null) {
                        log("окно " + point + ": у оператора уже есть клиент " + currentTicket(userId) + " - сначала завершите");
                        return;
                    }
                    send("TASK_INVITE_NEXT_CUSTOMER", userId);
                    break;
                case START:
                    if (state == null) {
                        log("окно " + point + ": начать приём - у оператора нет вызванного клиента");
                        return;
                    }
                    if (!state.contains("INVITED")) {
                        log("окно " + point + ": приём уже идёт (" + state + ")");
                        return;
                    }
                    send("TASK_START_CUSTOMER", userId);
                    break;
                case FINISH:
                    if (state == null) {
                        log("окно " + point + ": завершить - у оператора нет клиента");
                        return;
                    }
                    if (state.contains("INVITED")) {
                        // приём не начат (или нажатие 3 потерялось по радио) - начинаем и сразу завершаем,
                        // чтобы клиент не удалялся как "не явился"
                        log("окно " + point + ": приём не был начат - начинаю и завершаю");
                        send("TASK_START_CUSTOMER", userId);
                    }
                    send("TASK_FINISH_CUSTOMER", userId);
                    break;
            }
            log("окно " + point + " (user id " + userId + "): " + action + " выполнено, у оператора сейчас талон: " + currentTicket(userId));
            refreshClients(userId);
        } catch (Throwable ex) {
            log("окно " + point + ": " + action + " ОШИБКА " + rootMessage(ex));
            PortLog.error("PULT command " + action + " window=" + point, ex);
        }
    }

    /**
     * Вошёл ли оператор в программу: есть ли у него живая сессия на сервере (QSessions).
     * null - не удалось узнать (другая версия QSystem), тогда пульт не блокируем.
     */
    private static Boolean isLogged(Long userId) {
        try {
            final Class<?> cls = Class.forName("ru.apertum.qsystem.server.QSessions");
            final Object inst = cls.getMethod("getInstance").invoke(null);
            final Collection<?> sessions = (Collection<?>) cls.getMethod("getSessions").invoke(inst);
            synchronized (inst) {
                for (Object s : sessions) {
                    final Object u = call(s, "getUser");
                    final Object id = u == null ? null : call(u, "getId");
                    if (id instanceof Number && ((Number) id).longValue() == userId) {
                        final Object valid = call(s, "isValid");
                        return valid == null || Boolean.TRUE.equals(valid);
                    }
                }
            }
            return false;
        } catch (Throwable ex) {
            if (!loggedWarned) {
                loggedWarned = true;
                log("не удалось проверить, вошёл ли оператор (" + ex + ") - проверка отключена");
            }
            return null;
        }
    }

    private static volatile boolean loggedWarned;

    /**
     * Попросить программы операторов обновиться сразу, а не при следующем опросе.
     * Сервер QSystem сам так делает: шлёт по UDP id услуги, клиенты с этой услугой перечитывают своё состояние.
     */
    private static void refreshClients(Long userId) {
        try {
            final Class<?> propsCls = Class.forName("ru.apertum.qsystem.server.ServerProps");
            final Object props = propsCls.getMethod("getInstance").invoke(null);
            final Object netProps = props.getClass().getMethod("getProps").invoke(props);
            final int clientPort = ((Number) netProps.getClass().getMethod("getClientPort").invoke(netProps)).intValue();
            final Method broadcast = Class.forName("ru.apertum.qsystem.common.Uses").getMethod("sendUDPBroadcast", String.class, int.class);

            final java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            final Class<?> listCls = Class.forName("ru.apertum.qsystem.server.model.QUserList");
            final Object list = listCls.getMethod("getInstance").invoke(null);
            for (Object u : (Collection<?>) listCls.getMethod("getItems").invoke(list)) {
                final Object id = call(u, "getId");
                if (!(id instanceof Number) || ((Number) id).longValue() != userId) {
                    continue;
                }
                final Object plan = call(u, "getPlanServices");
                if (plan instanceof Collection) {
                    for (Object ps : (Collection<?>) plan) {
                        final Object srv = call(ps, "getService");
                        final Object sid = srv == null ? null : call(srv, "getId");
                        if (sid != null) {
                            ids.add(sid.toString());
                        }
                    }
                }
            }
            for (String sid : ids) {
                broadcast.invoke(null, sid, clientPort);
            }
            PortLog.info("PULT refresh clients port=" + clientPort + " services=" + ids);
        } catch (Throwable ex) {
            if (!refreshWarned) {
                refreshWarned = true;
                log("не удалось попросить программы операторов обновиться (" + ex + ") - обновятся при следующем опросе");
            }
        }
    }

    private static volatile boolean refreshWarned;

    /**
     * Окно -> id оператора. В таблице пультов вместо окна можно указать id=5 или name=Иванов.
     *
     * По номеру окна может найтись несколько пользователей (админ, удалённый, второй сотрудник с тем же окном).
     * Тогда берём того, кто сейчас работает: у кого есть клиент или кто в сети, у кого есть услуги,
     * а удалённых и выключенных пропускаем. Всех кандидатов пишем в лог, чтобы было видно, кого выбрали.
     */
    private Long findUserId(String point) throws Exception {
        final String p = point.trim();
        if (p.toLowerCase().startsWith("id=")) {
            return Long.valueOf(p.substring(3).trim());
        }
        final boolean byName = p.toLowerCase().startsWith("name=");
        final String want = byName ? p.substring(5).trim() : p;

        // мы внутри процесса сервера - берём операторов из его модели, без сети
        final Class<?> listCls = Class.forName("ru.apertum.qsystem.server.model.QUserList");
        final Object list = listCls.getMethod("getInstance").invoke(null);
        final Collection<?> users = (Collection<?>) listCls.getMethod("getItems").invoke(list);

        Object best = null;
        int bestScore = Integer.MIN_VALUE;
        final StringBuilder all = new StringBuilder();
        for (Object u : users) {
            final String uPoint = str(call(u, "getPoint"));
            final String uName = str(call(u, "getName"));
            final boolean match = byName ? want.equalsIgnoreCase(uName) : want.equalsIgnoreCase(uPoint);
            if (!match) {
                continue;
            }
            final int score = score(u);
            all.append("\n    id=").append(call(u, "getId")).append(" '").append(uName).append("' окно='").append(uPoint)
                    .append("' оценка=").append(score);
            if (score > bestScore) {
                bestScore = score;
                best = u;
            }
        }
        if (best == null || bestScore < 0) {
            log("окно " + point + ": подходящий оператор не найден" + (all.length() > 0 ? ", кандидаты:" + all : ""));
            return null;
        }
        final Long id = ((Number) call(best, "getId")).longValue();
        log("окно " + point + " -> оператор id=" + id + " '" + str(call(best, "getName")) + "'"
                + (all.toString().split("\n").length > 2 ? ", кандидаты:" + all : ""));
        return id;
    }

    /**
     * Насколько пользователь похож на того, кто сейчас сидит за окном. Отрицательная - не брать вообще.
     */
    private static int score(Object u) {
        int s = 0;
        if (call(u, "getDeleted") != null) {
            return -100;
        }
        final Object enable = call(u, "getEnable");
        if (enable instanceof Number && ((Number) enable).intValue() != 1) {
            return -100;
        }
        // сейчас обслуживает клиента - точно он
        if (call(u, "getCustomer") != null) {
            s += 100;
        }
        // в сети (есть не во всех версиях QSystem)
        if (Boolean.TRUE.equals(call(u, "getCurrentState"))) {
            s += 50;
        }
        final Object plan = call(u, "getPlanServices");
        if (plan instanceof Collection && !((Collection<?>) plan).isEmpty()) {
            s += 10;
        } else {
            // без услуг вызывать некого
            s -= 20;
        }
        if (Boolean.TRUE.equals(call(u, "getAdminAccess"))) {
            s -= 5;
        }
        return s;
    }

    /**
     * Статус клиента, который сейчас у оператора (STATE_INVITED, STATE_WORK...), или null, если клиента нет.
     */
    private static String customerState(Long userId) {
        try {
            final Class<?> listCls = Class.forName("ru.apertum.qsystem.server.model.QUserList");
            final Object list = listCls.getMethod("getInstance").invoke(null);
            for (Object u : (Collection<?>) listCls.getMethod("getItems").invoke(list)) {
                final Object id = call(u, "getId");
                if (id instanceof Number && ((Number) id).longValue() == userId) {
                    final Object c = call(u, "getCustomer");
                    return c == null ? null : str(call(c, "getState"));
                }
            }
        } catch (Exception ex) {
            log("не удалось узнать статус клиента оператора " + userId + ": " + ex);
        }
        return null;
    }

    /**
     * Какой талон сейчас у оператора на сервере - для лога.
     */
    private static String currentTicket(Long userId) {
        try {
            final Class<?> listCls = Class.forName("ru.apertum.qsystem.server.model.QUserList");
            final Object list = listCls.getMethod("getInstance").invoke(null);
            for (Object u : (Collection<?>) listCls.getMethod("getItems").invoke(list)) {
                final Object id = call(u, "getId");
                if (id instanceof Number && ((Number) id).longValue() == userId) {
                    final Object c = call(u, "getCustomer");
                    if (c == null) {
                        return "нет";
                    }
                    return str(call(c, "getPrefix")) + str(call(c, "getNumber")) + " (" + str(call(c, "getState")) + ")";
                }
            }
        } catch (Exception ex) {
            return "? " + ex;
        }
        return "?";
    }

    /**
     * Вызов геттера без исключений: если метода нет в этой версии QSystem - null.
     */
    private static Object call(Object o, String method) {
        try {
            return o.getClass().getMethod(method).invoke(o);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private void send(String taskField, Long userId) throws Exception {
        final String task = (String) Class.forName("ru.apertum.qsystem.common.Uses").getField(taskField).get(null);

        final Class<?> paramsCls = Class.forName("ru.apertum.qsystem.common.cmd.CmdParams");
        final Object params = paramsCls.newInstance();
        final Field userField = paramsCls.getField("userId");
        userField.set(params, userId);
        // QSystem 23 при завершении требует результат работы: -1 = без результата (как кнопка в клиенте)
        try {
            paramsCls.getField("resultId").set(params, -1L);
        } catch (NoSuchFieldException ex) {
            // в старых версиях поля нет
        }

        final Class<?> netPropCls = Class.forName("ru.apertum.qsystem.common.model.INetProperty");
        final Object netProp = Proxy.newProxyInstance(netPropCls.getClassLoader(), new Class<?>[]{netPropCls},
                (proxy, method, args) -> netProperty(method));

        final Class<?> commander = Class.forName("ru.apertum.qsystem.common.NetCommander");
        final Method sendMethod = commander.getMethod("send", netPropCls, String.class, paramsCls);
        try {
            sendMethod.invoke(null, netProp, task, params);
        } catch (InvocationTargetException ex) {
            throw ex.getCause() instanceof Exception ? (Exception) ex.getCause() : ex;
        }
    }

    /**
     * Реализация INetProperty: адрес и порт сервера QSystem.
     */
    private Object netProperty(Method method) throws Exception {
        final String name = method.getName();
        if ("getAddress".equals(name) || "getServerAddress".equals(name)) {
            return InetAddress.getByName(host);
        }
        if (name.toLowerCase().contains("port")) {
            return port;
        }
        if ("toString".equals(name)) {
            return host + ":" + port;
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(this);
        }
        if ("equals".equals(name)) {
            return false;
        }
        final Class<?> rt = method.getReturnType();
        if (rt == boolean.class) {
            return false;
        }
        if (rt.isPrimitive() && rt != void.class) {
            return 0;
        }
        return null;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static void log(String s) {
        System.out.println("SOLT PULT: " + s);
        PortLog.info("PULT " + s);
    }
}
