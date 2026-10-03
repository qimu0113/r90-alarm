import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * R90 核心算式的真 JVM 验证（Java 镜像实现）。
 * 目的：在真实 JDK 21 上验证「向上取整 + 进位 + dayDiff 跟随」的正确性，
 * 而不是靠脚本自说自话。
 *
 * 与 Kotlin 版 R90Calculator 逐行等价：
 *   wake   = now + fall + 90*cycles
 *   rounded= (wake.second==0) ? wake : wake.truncatedTo(MINUTES).plusMinutes(1)
 *   dayDiff= DAYS.between(now.toLocalDate(), rounded.toLocalDate())
 */
public class CeilVerify {

    static final int CYCLE_MINUTES = 90;
    static final int DEFAULT_FALL = 15;
    static final int[] CYCLES = {1,2,3,4,5,6,7,8,9};

    static class R {
        int cycles; ZonedDateTime raw; ZonedDateTime rounded;
        int hour24, minute, dayDiff;
        R(int c, ZonedDateTime raw, ZonedDateTime rounded, int h, int m, int d) {
            this.cycles=c; this.raw=raw; this.rounded=rounded; this.hour24=h; this.minute=m; this.dayDiff=d;
        }
        public String toString() { return String.format("N=%d %02d:%02d dayDiff=%d", cycles, hour24, minute, dayDiff); }
    }

    static List<R> calculateAll(ZonedDateTime now, int fall) {
        if (fall < 0) throw new IllegalArgumentException("fallAsleepMinutes must be >= 0, got " + fall);
        LocalDate today = now.toLocalDate();
        List<R> out = new ArrayList<>();
        for (int c : CYCLES) {
            ZonedDateTime wake = now.plusMinutes(fall).plusMinutes((long) CYCLE_MINUTES * c);
            ZonedDateTime rounded = (wake.getSecond() == 0)
                    ? wake
                    : wake.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
            int dayDiff = (int) ChronoUnit.DAYS.between(today, rounded.toLocalDate());
            out.add(new R(c, wake, rounded, rounded.getHour(), rounded.getMinute(), dayDiff));
        }
        return out;
    }

    static String relativeDayLabel(int dayDiff) {
        switch (dayDiff) {
            case 0: return "今天";
            case 1: return "明天";
            case 2: return "后天";
            default: return "第 " + (dayDiff + 1) + " 天";
        }
    }

    // ---------- 断言框架 ----------
    static int pass = 0, fail = 0;
    static List<String> failures = new ArrayList<>();

    static void check(String name, boolean cond, String detail) {
        if (cond) { pass++; }
        else { fail++; failures.add(name + " | " + detail); System.out.println("  ✗ " + name + " | " + detail); }
    }

    static ZonedDateTime t(int h, int m, int s) {
        return LocalDate.of(2026, 9, 28).atTime(h, m, s).atZone(ZoneId.of("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        System.out.println("=== R90 向上取整 · 真 JVM 验证 (JDK " + System.getProperty("java.version") + ") ===\n");

        // --- 用例 1：用户给的原例 04:32:59 -> 04:33 ---
        System.out.println("[1] 用户原例：04:32:59 必须进位到 04:33");
        {
            ZonedDateTime now = t(3, 2, 59); // fall=15 -> wake=03:17:59 + 90 = 04:47:59? 不对，直接构造目标
            // 直接验证取整函数本身：造 wake=04:32:59
            ZonedDateTime wake = t(4, 32, 59);
            ZonedDateTime rounded = wake.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
            check("04:32:59 -> 04:33", rounded.getHour()==4 && rounded.getMinute()==33,
                    "实际 " + String.format("%02d:%02d", rounded.getHour(), rounded.getMinute()));
            // 也走一遍完整算式：now=02:56:59 fall=15 -> wake=03:11:59 + 90 = 04:41:59 ... 
            // 换个能命中 04:32:59 的输入：now + 15 + 90*N = 04:32:59
            // N=2: 04:32:59 - 195min = 01:17:59
            ZonedDateTime now2 = LocalDate.of(2026,9,28).atTime(1,17,59).atZone(zone);
            List<R> rs = calculateAll(now2, 15);
            R n2 = rs.get(1); // N=2
            check("完整算式命中 04:33 (N=2)", n2.hour24==4 && n2.minute==33,
                    "实际 " + String.format("%02d:%02d", n2.hour24, n2.minute));
        }

        // --- 用例 2：秒为 0 不进位 ---
        System.out.println("\n[2] 秒为 0 时不进位");
        {
            List<R> rs = calculateAll(t(1, 15, 0), 15); // N=1 -> 01:30 + 90 = 03:00:00
            R n1 = rs.get(0);
            check("03:00:00 保持 03:00", n1.hour24==3 && n1.minute==0 && n1.raw.getSecond()==0,
                    "实际 " + String.format("%02d:%02d", n1.hour24, n1.minute));
        }

        // --- 用例 3：04:59:30 -> 05:00（小时进位） ---
        System.out.println("\n[3] 进位跨小时：04:59:30 -> 05:00");
        {
            // now + 15 + 90*N = 04:59:30 ; N=1 -> 04:59:30-105 = 03:14:30
            ZonedDateTime now = LocalDate.of(2026,9,28).atTime(3,14,30).atZone(zone);
            List<R> rs = calculateAll(now, 15);
            R n1 = rs.get(0);
            check("04:59:30 -> 05:00", n1.hour24==5 && n1.minute==0,
                    "实际 " + String.format("%02d:%02d", n1.hour24, n1.minute));
        }

        // --- 用例 4：23:59:30 -> 次日 00:00，dayDiff 必须变 1 ---
        System.out.println("\n[4] 进位跨天：23:59:30 -> 次日 00:00，dayDiff 必须跟随");
        {
            // now + 15 + 90*N = 23:59:30 ; N=1 -> 22:14:30
            ZonedDateTime now = LocalDate.of(2026,9,28).atTime(22,14,30).atZone(zone);
            List<R> rs = calculateAll(now, 15);
            R n1 = rs.get(0);
            check("23:59:30 -> 00:00", n1.hour24==0 && n1.minute==0,
                    "实际 " + String.format("%02d:%02d", n1.hour24, n1.minute));
            check("dayDiff 必须为 1（否则 UI 会说'今天'）", n1.dayDiff==1, "实际 dayDiff=" + n1.dayDiff);
            check("标签应为 '明天'", "明天".equals(relativeDayLabel(n1.dayDiff)),
                    "实际 " + relativeDayLabel(n1.dayDiff));
        }

        // --- 用例 5：取整后仍在同一天时 dayDiff 保持 0 ---
        System.out.println("\n[5] 同日进位：dayDiff 保持 0");
        {
            ZonedDateTime now = LocalDate.of(2026,9,28).atTime(1,17,59).atZone(zone);
            List<R> rs = calculateAll(now, 15);
            R n2 = rs.get(1);
            check("04:33 dayDiff=0", n2.dayDiff==0, "实际 " + n2.dayDiff);
        }

        // --- 用例 6：全量扫描，边界与合法性 ---
        System.out.println("\n[6] 全量扫描：432 个起点 × 9 周期 = 3888 结果");
        {
            int total = 0, bad = 0, crossDay = 0, carryIssue = 0;
            LocalDate base = LocalDate.of(2026, 9, 28);
            for (int h = 0; h < 24; h++) {
                for (int m = 0; m < 60; m += 10) {
                    for (int s = 0; s < 60; s += 20) {
                        ZonedDateTime now = base.atTime(h, m, s).atZone(zone);
                        List<R> rs = calculateAll(now, 15);
                        for (R r : rs) {
                            total++;
                            // 合法性
                            if (r.hour24 < 0 || r.hour24 > 23 || r.minute < 0 || r.minute > 59) {
                                bad++; if (bad <= 5) System.out.println("  ✗ 越界 " + r);
                            }
                            // 取整后必须 >= 原始（向上）
                            if (r.rounded.isBefore(r.raw)) {
                                carryIssue++; if (carryIssue <= 5) System.out.println("  ✗ 取整后反而变小 " + r.raw + " -> " + r.rounded);
                            }
                            // 取整后与原始差值必须 < 60 秒
                            long diffSec = Math.abs(ChronoUnit.SECONDS.between(r.raw, r.rounded));
                            if (diffSec >= 60 && r.raw.getSecond() != 0) {
                                carryIssue++; if (carryIssue <= 5) System.out.println("  ✗ 取整偏差过大 " + diffSec + "s " + r.raw);
                            }
                            // dayDiff 必须与取整后日期一致
                            int expectDay = (int) ChronoUnit.DAYS.between(base, r.rounded.toLocalDate());
                            if (r.dayDiff != expectDay) {
                                carryIssue++; if (carryIssue <= 5) System.out.println("  ✗ dayDiff 不一致 " + r);
                            }
                            if (r.dayDiff > 0) crossDay++;
                        }
                    }
                }
            }
            check("1296 结果全部合法 (0<=h<=23, 0<=m<=59)", bad == 0, "越界 " + bad + " 个");
            check("向上取整无反向/无超差", carryIssue == 0, "异常 " + carryIssue + " 个");
            check("扫描总数 = 3888", total == 3888, "实际 " + total);
            System.out.println("  · 总结果 " + total + "，其中跨天 " + crossDay + " 个，跨天占比 "
                    + String.format("%.1f%%", crossDay * 100.0 / total));
        }

        // --- 用例 7：fall 可调（Q3）验证 ---
        System.out.println("\n[7] 准备入睡时长可调（fall = 0 / 15 / 30 / 60）");
        {
            ZonedDateTime now = t(10, 0, 0); // 10:00:00 整点
            int[] falls = {0, 15, 30, 60};
            // now=10:00 时，N=1 起床 = 10:00 + fall + 90min
            int[] expectN1H = {11, 11, 12, 12};
            int[] expectN1M = {30, 45, 0, 30};
            for (int i = 0; i < falls.length; i++) {
                List<R> rs = calculateAll(now, falls[i]);
                R n1 = rs.get(0);
                boolean ok = n1.hour24 == expectN1H[i] && n1.minute == expectN1M[i];
                check("fall=" + falls[i] + " -> N=1 应为 " + expectN1H[i] + ":" + String.format("%02d", expectN1M[i]),
                        ok, "实际 " + String.format("%02d:%02d", n1.hour24, n1.minute));
            }
        }

        // --- 用例 8：非法 fall 应拒绝 ---
        System.out.println("\n[8] 非法 fall 应被拒绝");
        {
            boolean threw = false;
            try { calculateAll(t(10,0,0), -1); }
            catch (RuntimeException e) { threw = true; }
            check("fall=-1 抛异常", threw, "未抛异常");
        }

        // ---------- 汇总 ----------
        System.out.println("\n=== 汇总 ===");
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) {
            System.out.println("\n失败明细：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        } else {
            System.out.println("全部通过。");
        }
    }
}
