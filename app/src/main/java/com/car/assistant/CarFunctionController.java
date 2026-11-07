package com.car.assistant;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * ECARX AdaptAPI 车辆功能控制器（全反射实现，无需编译期依赖 ecarx.adapterapi.jar）。
 *
 * 调用链（与车机 com.ecarx.qq.tool 的实现一致）：
 *   com.ecarx.xui.adaptapi.car.Car.create(context)  -> ICar
 *   ICar.getICarFunction()                          -> ICarFunction
 *   ICarFunction.setFunctionValue(id, value)        -> boolean
 *   ICarFunction.getFunctionValue(id)               -> int
 *
 * 功能 ID：
 *   驾驶模式   0x22010100，写入 0x220101xx 模式值
 *   智能启停   0x20020100，1 = 开，0 = 关
 */
public final class CarFunctionController {
    private static final String TAG = "CarAssistant";

    public static final int FUNC_DRIVE_MODE = 0x22010100;
    public static final int FUNC_ENGINE_STOP_START = 0x20020100;

    // 驾驶模式取值（来自 ecarx adaptapi IDriveMode 接口常量）
    public static final int MODE_ECO = 0x22010101;
    public static final int MODE_COMFORT = 0x22010102;
    public static final int MODE_DYNAMIC = 0x22010103;
    public static final int MODE_XC = 0x22010104;      // 越野
    public static final int MODE_POWER = 0x22010108;
    public static final int MODE_NORMAL = 0x22010111;
    public static final int MODE_CUSTOM = 0x22010140;  // 个性化

    private static Object sCarFunction;
    private static Object sModeWatcherProxy;

    /** 驾驶模式变化回调（在 binder 线程触发，注意切线程） */
    public interface ModeChangeListener {
        void onModeChanged(int newMode);
    }

    private CarFunctionController() {}

    /** 获取 ICarFunction；未就绪时返回 null（可稍后重试）。 */
    public static synchronized Object getCarFunction(Context ctx) {
        if (sCarFunction != null) return sCarFunction;
        Context app = ctx.getApplicationContext();

        // 主路径：Car.create(context)
        try {
            Class<?> carCls = Class.forName("com.ecarx.xui.adaptapi.car.Car");
            Object iCar = carCls.getMethod("create", Context.class).invoke(null, app);
            if (iCar != null) {
                Object fn = iCar.getClass().getMethod("getICarFunction").invoke(iCar);
                if (fn != null) {
                    sCarFunction = fn;
                    Log.i(TAG, "ICarFunction ready via Car.create");
                    return sCarFunction;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Car.create path failed: " + t);
        }

        // 兜底路径：ecarx.mycar.sdkimpl.AdaptapiConnect
        try {
            Class<?> ac = Class.forName("ecarx.mycar.sdkimpl.AdaptapiConnect");
            Object inst = ac.getMethod("getInstance").invoke(null);
            if (inst != null) {
                Object iCar = inst.getClass().getMethod("getiCar").invoke(inst);
                if (iCar != null) {
                    Object fn = iCar.getClass().getMethod("getICarFunction").invoke(iCar);
                    if (fn != null) {
                        sCarFunction = fn;
                        Log.i(TAG, "ICarFunction ready via AdaptapiConnect");
                        return sCarFunction;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "AdaptapiConnect path failed: " + t);
        }
        return null;
    }

    /** 连接失败时清除缓存，下次重连（例如车辆信号晚于开机就绪）。 */
    public static synchronized void reset() {
        sCarFunction = null;
    }

    private static int getFunctionValue(Object fn, int functionId) throws Exception {
        Method m = fn.getClass().getMethod("getFunctionValue", int.class);
        Object r = m.invoke(fn, functionId);
        return (r instanceof Integer) ? (Integer) r : -1;
    }

    /** 读取当前驾驶模式；失败返回 -1。 */
    public static int getDriveMode(Context ctx) {
        Object fn = getCarFunction(ctx);
        if (fn == null) return -1;
        try {
            return getFunctionValue(fn, FUNC_DRIVE_MODE);
        } catch (Throwable t) {
            Log.w(TAG, "getDriveMode: " + t);
            return -1;
        }
    }

    /**
     * 写入驾驶模式（v1.1 安全版）：
     *   1) 优先三参 zone=1（驾驶员区），避免无 zone 写入使模式服务失步
     *      —— 实测：裸两参写入配置外模式会导致物理按键失效，需重启 vnsd 恢复
     *   2) zone=2 兜底
     *   3) 两参无 zone 仅作最后手段
     */
    public static boolean setDriveMode(Context ctx, int mode) {
        Object fn = getCarFunction(ctx);
        if (fn == null) return false;
        for (int zone : new int[]{1, 2}) {
            try {
                Method m3 = fn.getClass().getMethod(
                        "setFunctionValue", int.class, int.class, int.class);
                Object r = m3.invoke(fn, FUNC_DRIVE_MODE, zone, mode);
                Log.i(TAG, "setDriveMode 3-arg zone=" + zone
                        + " mode=0x" + Integer.toHexString(mode) + " -> " + r);
                if (Boolean.TRUE.equals(r)) return true;
            } catch (Throwable t) {
                Log.w(TAG, "setDriveMode 3-arg zone=" + zone + ": " + t);
            }
        }
        try {
            Method m2 = fn.getClass().getMethod("setFunctionValue", int.class, int.class);
            Object r = m2.invoke(fn, FUNC_DRIVE_MODE, mode);
            Log.i(TAG, "setDriveMode 2-arg mode=0x" + Integer.toHexString(mode) + " -> " + r);
            if (Boolean.TRUE.equals(r)) return true;
        } catch (Throwable t) {
            Log.w(TAG, "setDriveMode 2-arg: " + t);
        }
        return false;
    }

    /** 智能启停当前状态；true=开。读取失败按 true 处理（避免误报）。 */
    public static boolean isIssOn(Context ctx) {
        Object fn = getCarFunction(ctx);
        if (fn == null) return true;
        try {
            return getFunctionValue(fn, FUNC_ENGINE_STOP_START) == 1;
        } catch (Throwable t) {
            Log.w(TAG, "isIssOn: " + t);
            return true;
        }
    }

    /** 设置智能启停开关；成功返回 true。 */
    public static boolean setIss(Context ctx, boolean on) {
        Object fn = getCarFunction(ctx);
        if (fn == null) return false;
        try {
            Method m = fn.getClass().getMethod("setFunctionValue", int.class, int.class);
            Object r = m.invoke(fn, FUNC_ENGINE_STOP_START, on ? 1 : 0);
            Log.i(TAG, "setIss " + on + " -> " + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            Log.w(TAG, "setIss: " + t);
            return false;
        }
    }

    /**
     * 注册驾驶模式值监听（v1.2）。
     * ICarFunction.registerFunctionValueWatcher(int, IFunctionValueWatcher)
     * 回调接口 ICarFunction$IFunctionValueWatcher 是 interface，用动态代理实现。
     */
    public static synchronized boolean registerDriveModeWatcher(Context ctx,
                                                                final ModeChangeListener l) {
        Object fn = getCarFunction(ctx);
        if (fn == null) return false;
        try {
            Class<?> itf = Class.forName(
                    "com.ecarx.xui.adaptapi.car.base.ICarFunction$IFunctionValueWatcher");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    itf.getClassLoader(), new Class[]{itf},
                    (p, method, args) -> {
                        // 签名: onFunctionValueChanged(int functionId, int areaId, int value)
                        // 注意第 2 个参数是 areaId，真正的值在最后一位（v1.3 修正）
                        if ("onFunctionValueChanged".equals(method.getName())
                                && args != null && args.length >= 2
                                && args[0] instanceof Integer
                                && (Integer) args[0] == FUNC_DRIVE_MODE) {
                            int value = (args.length >= 3 && args[args.length - 1] instanceof Integer)
                                    ? (Integer) args[args.length - 1]
                                    : (Integer) args[1];
                            Log.i(TAG, "watcher raw: id=0x" + Integer.toHexString((Integer) args[0])
                                    + " area=" + (args.length >= 3 ? args[1] : "-")
                                    + " value=0x" + Integer.toHexString(value));
                            l.onModeChanged(value);
                        }
                        return null;
                    });
            Method reg = fn.getClass().getMethod(
                    "registerFunctionValueWatcher", int.class, itf);
            Object r = reg.invoke(fn, FUNC_DRIVE_MODE, proxy);
            Log.i(TAG, "registerDriveModeWatcher -> " + r);
            sModeWatcherProxy = proxy;
            return !Boolean.FALSE.equals(r);
        } catch (Throwable t) {
            Log.w(TAG, "registerDriveModeWatcher: " + t);
            return false;
        }
    }

    /** 取消驾驶模式监听。 */
    public static synchronized void unregisterDriveModeWatcher(Context ctx) {
        Object fn = sCarFunction;
        Object proxy = sModeWatcherProxy;
        sModeWatcherProxy = null;
        if (fn == null || proxy == null) return;
        try {
            Class<?> itf = Class.forName(
                    "com.ecarx.xui.adaptapi.car.base.ICarFunction$IFunctionValueWatcher");
            fn.getClass().getMethod("unregisterFunctionValueWatcher", itf)
                    .invoke(fn, proxy);
            Log.i(TAG, "unregisterDriveModeWatcher done");
        } catch (Throwable t) {
            Log.w(TAG, "unregisterDriveModeWatcher: " + t);
        }
    }

    /** 是否为已知/可识别的驾驶模式值（用于判断信号是否已就绪）。 */
    public static boolean isKnownMode(int mode) {
        switch (mode) {
            case MODE_ECO:
            case MODE_COMFORT:
            case MODE_DYNAMIC:
            case MODE_XC:
            case MODE_POWER:
            case MODE_NORMAL:
            case MODE_CUSTOM:
                return true;
            default:
                return false;
        }
    }

    /** 模式值 -> 中文名（用于界面显示）。 */
    public static String modeName(int mode) {
        switch (mode) {
            case MODE_ECO: return "经济";
            case MODE_COMFORT: return "舒适";
            case MODE_DYNAMIC: return "运动";
            case MODE_XC: return "越野";
            case MODE_POWER: return "动力";
            case MODE_NORMAL: return "普通";
            case MODE_CUSTOM: return "个性化";
            default: return "未知(0x" + Integer.toHexString(mode) + ")";
        }
    }
}
