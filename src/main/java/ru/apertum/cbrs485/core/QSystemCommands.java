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

    private final String host;
    private final int port;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "ClientboardRS485Plugin-pult");
        t.setDaemon(true);
        return t;
    });

    public QSystemCommands(String host, int port) {
        this.host = host;
        this.port = port;
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
            switch (action) {
                case INVITE:
                    send("TASK_INVITE_NEXT_CUSTOMER", userId);
                    break;
                case START:
                    send("TASK_START_CUSTOMER", userId);
                    break;
                case FINISH:
                    try {
                        send("TASK_FINISH_CUSTOMER", userId);
                    } catch (Exception ex) {
                        // приём не начинали - клиент только вызван, тогда "не явился"
                        log("окно " + point + ": завершить не вышло (" + rootMessage(ex) + "), пробую \"не явился\"");
                        send("TASK_KILL_NEXT_CUSTOMER", userId);
                    }
                    break;
                default:
                    return;
            }
            log("окно " + point + " (user id " + userId + "): " + action + " выполнено");
        } catch (Throwable ex) {
            log("окно " + point + ": " + action + " ОШИБКА " + rootMessage(ex));
            PortLog.error("PULT command " + action + " window=" + point, ex);
        }
    }

    /**
     * Окно -> id оператора. В таблице пультов вместо окна можно сразу указать id=5.
     */
    private Long findUserId(String point) throws Exception {
        if (point.toLowerCase().startsWith("id=")) {
            return Long.valueOf(point.substring(3).trim());
        }
        // мы внутри процесса сервера - берём операторов из его модели, без сети
        final Class<?> listCls = Class.forName("ru.apertum.qsystem.server.model.QUserList");
        final Object list = listCls.getMethod("getInstance").invoke(null);
        final Collection<?> users = (Collection<?>) listCls.getMethod("getItems").invoke(list);
        for (Object u : users) {
            final Object p = u.getClass().getMethod("getPoint").invoke(u);
            if (p != null && point.trim().equalsIgnoreCase(p.toString().trim())) {
                return ((Number) u.getClass().getMethod("getId").invoke(u)).longValue();
            }
        }
        return null;
    }

    private void send(String taskField, Long userId) throws Exception {
        final String task = (String) Class.forName("ru.apertum.qsystem.common.Uses").getField(taskField).get(null);

        final Class<?> paramsCls = Class.forName("ru.apertum.qsystem.common.cmd.CmdParams");
        final Object params = paramsCls.newInstance();
        final Field userField = paramsCls.getField("userId");
        userField.set(params, userId);

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
