package top.wcpe.mc.mpmt.core.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;

/** 服务端封禁编排：异步加载、串行持久化变更与在线会话断开。 */
public final class BanService {

    /** 初始化状态。 */
    public enum State {
        NEW,
        INITIALIZING,
        READY,
        FAILED
    }

    private static final Logger LOGGER = Logger.getLogger(BanService.class.getName());
    private static final String NAMESPACE = "machine-code-bans";
    private static final String SNAPSHOT_KEY = "snapshot";
    private static final String DISCONNECT_REASON_PREFIX = "你的客户端标识已被封禁：";

    private final BanRegistry registry;
    private final SessionRegistry sessions;
    private final PersistencePort persistence;
    private final SchedulerPort scheduler;
    private final ConnectionControlPort connections;
    private final BanSnapshotCodec codec;
    private final Object lifecycleLock = new Object();
    private final Deque<QueuedOperation> operations = new ArrayDeque<>();

    private volatile State state = State.NEW;
    private CompletableFuture<Void> initialization;
    private boolean workerScheduled;

    public BanService(
            BanRegistry registry,
            SessionRegistry sessions,
            PersistencePort persistence,
            SchedulerPort scheduler,
            ConnectionControlPort connections) {
        this(registry, sessions, persistence, scheduler, connections, new BanSnapshotCodec());
    }

    BanService(
            BanRegistry registry,
            SessionRegistry sessions,
            PersistencePort persistence,
            SchedulerPort scheduler,
            ConnectionControlPort connections,
            BanSnapshotCodec codec) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.sessions = Objects.requireNonNull(sessions, "sessions 不能为空");
        this.persistence = Objects.requireNonNull(persistence, "persistence 不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不能为空");
        this.connections = Objects.requireNonNull(connections, "connections 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
    }

    /** 异步加载持久化快照；重复调用观察同一次初始化。 */
    public CompletableFuture<Void> initialize() {
        synchronized (lifecycleLock) {
            if (initialization == null) {
                state = State.INITIALIZING;
                initialization = submit(this::loadSnapshot);
            }
            return futureView(initialization);
        }
    }

    /** 当前初始化状态。 */
    public State state() {
        return state;
    }

    /** 串行封禁；持久化成功后才提交内存状态并调度断开。 */
    public CompletableFuture<Void> ban(MachineCode code, String reason) {
        Objects.requireNonNull(code, "code 不能为空");
        Objects.requireNonNull(reason, "reason 不能为空");
        return submit(() -> applyBan(code, reason));
    }

    /** 串行解封；持久化成功后才提交内存状态。 */
    public CompletableFuture<Void> unban(MachineCode code) {
        Objects.requireNonNull(code, "code 不能为空");
        return submit(() -> applyUnban(code));
    }

    /** 查询当前内存封禁状态。 */
    public boolean isBanned(MachineCode code) {
        return registry.isBanned(code);
    }

    /** 返回当前不可变封禁快照。 */
    public List<BanEntry> list() {
        return registry.list();
    }

    private void loadSnapshot() {
        try {
            Optional<String> snapshot = persistence.read(NAMESPACE, SNAPSHOT_KEY);
            registry.replaceAll(snapshot.isPresent() ? codec.decode(snapshot.get()) : new ArrayList<BanEntry>());
            state = State.READY;
        } catch (RuntimeException error) {
            state = State.FAILED;
            throw error;
        }
    }

    private void applyBan(MachineCode code, String reason) {
        requireReady();
        List<BanEntry> updated = without(registry.list(), code);
        updated.add(new BanEntry(code, reason));
        persistAndReplace(updated);
        scheduleDisconnects(code, reason);
    }

    private void applyUnban(MachineCode code) {
        requireReady();
        persistAndReplace(without(registry.list(), code));
    }

    private void persistAndReplace(List<BanEntry> updated) {
        persistence.write(NAMESPACE, SNAPSHOT_KEY, codec.encode(updated));
        registry.replaceAll(updated);
    }

    private void scheduleDisconnects(MachineCode code, String reason) {
        for (SessionRegistry.Session session : sessions.findByMachineCode(code)) {
            EntityRef entity = connections.entityOf(session.getConnection());
            scheduler.runForEntity(entity, () -> disconnectIfCurrent(session, code, reason));
        }
    }

    private void disconnectIfCurrent(SessionRegistry.Session session, MachineCode code, String reason) {
        if (sessions.isCurrent(session) && registry.isBanned(code)) {
            connections.disconnect(session.getConnection(), DISCONNECT_REASON_PREFIX + reason);
        }
    }

    private void requireReady() {
        if (state != State.READY) {
            throw new IllegalStateException("封禁服务尚未就绪，当前状态：" + state);
        }
    }

    private CompletableFuture<Void> submit(Runnable action) {
        QueuedOperation operation = new QueuedOperation(action);
        boolean schedule;
        synchronized (operations) {
            operations.addLast(operation);
            schedule = !workerScheduled;
            workerScheduled = true;
        }
        if (schedule) {
            scheduleWorker();
        }
        return operation.future;
    }

    private void scheduleWorker() {
        try {
            scheduler.runAsync(this::runNext);
        } catch (RuntimeException error) {
            failHead(error);
        }
    }

    private void runNext() {
        QueuedOperation operation;
        synchronized (operations) {
            operation = operations.peekFirst();
        }
        try {
            operation.action.run();
            operation.future.complete(null);
        } catch (RuntimeException error) {
            LOGGER.log(Level.SEVERE, "封禁服务异步操作失败", error);
            operation.future.completeExceptionally(error);
        } finally {
            finishHead();
        }
    }

    private void failHead(RuntimeException error) {
        QueuedOperation operation;
        boolean schedule;
        synchronized (operations) {
            operation = operations.removeFirst();
            schedule = !operations.isEmpty();
            workerScheduled = schedule;
        }
        LOGGER.log(Level.SEVERE, "封禁服务异步调度失败", error);
        operation.future.completeExceptionally(error);
        if (schedule) {
            scheduleWorker();
        }
    }

    private void finishHead() {
        boolean schedule;
        synchronized (operations) {
            operations.removeFirst();
            schedule = !operations.isEmpty();
            workerScheduled = schedule;
        }
        if (schedule) {
            scheduleWorker();
        }
    }

    private static List<BanEntry> without(List<BanEntry> entries, MachineCode code) {
        List<BanEntry> result = new ArrayList<>();
        for (BanEntry entry : entries) {
            if (!entry.getCode().equals(code)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static CompletableFuture<Void> futureView(CompletableFuture<Void> source) {
        return source.thenApply(ignored -> null);
    }

    private static final class QueuedOperation {
        private final Runnable action;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private QueuedOperation(Runnable action) {
            this.action = action;
        }
    }
}
