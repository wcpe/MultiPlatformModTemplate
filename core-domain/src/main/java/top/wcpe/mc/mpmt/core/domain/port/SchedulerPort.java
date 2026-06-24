package top.wcpe.mc.mpmt.core.domain.port;

import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/**
 * 调度端口（L0）：按<b>归属</b>把任务调度到正确的执行线程（ADR-0013）。
 *
 * <p>Folia 无单一主线程、区域化多线程，碰某实体 / 方块必须调度到拥有它的线程，否则触发线程检查异常。
 * 故触碰世界 / 实体状态的任务<b>必须</b>经带归属入口（{@link #runForEntity} / {@link #runForLocation} /
 * {@link #runGlobal}），<b>禁止</b>无归属地碰世界态。非 Folia 平台这些归属统一退化为服务端主线程。
 *
 * <p>各平台 L3 适配实现本端口；涉及调度 / 线程的端口须按平台写契约测试，不指望同一份 L0 测试通吃（ADR-0013）。
 */
public interface SchedulerPort {

    /** 调度到实体所属线程（Folia: EntityScheduler；他平台: 主线程）。 */
    void runForEntity(EntityRef entity, Runnable task);

    /** 调度到坐标所属区域线程（Folia: RegionScheduler byLocation；他平台: 主线程）。 */
    void runForLocation(WorldRef world, int x, int z, Runnable task);

    /** 调度到全局线程（Folia: GlobalRegionScheduler；他平台: 主线程）。 */
    void runGlobal(Runnable task);

    /** 调度到异步线程，用于阻塞 IO / 远程调用，不得占用主线程或 tick。 */
    void runAsync(Runnable task);

    /**
     * 注册周期任务（单位：tick）。
     *
     * @param delayTicks  首次执行前的延迟
     * @param periodTicks 两次执行之间的周期
     * @param task        任务体；若碰世界 / 实体态，须在体内再经带归属入口切线程
     * @return 可取消句柄，调用方负责 {@link AutoCloseable#close()} 释放（防任务泄露）
     */
    AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task);
}
