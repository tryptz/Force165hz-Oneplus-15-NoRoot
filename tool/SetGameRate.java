import java.lang.reflect.Method;

public class SetGameRate {
    public static void main(String[] args) throws Exception {
        String pkg = args[0];
        int fps = Integer.parseInt(args[1]);

        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object binder = sm.getMethod("getService", String.class).invoke(null, "oplusscreenmode");
        if (binder == null) { System.out.println("oplusscreenmode service not found"); return; }

        Class<?> parcelCls = Class.forName("android.os.Parcel");
        Method obtain = parcelCls.getMethod("obtain");
        Method recycle = parcelCls.getMethod("recycle");
        Method writeInterfaceToken = parcelCls.getMethod("writeInterfaceToken", String.class);
        Method writeString = parcelCls.getMethod("writeString", String.class);
        Method writeInt = parcelCls.getMethod("writeInt", int.class);
        Method readException = parcelCls.getMethod("readException");
        Method readInt = parcelCls.getMethod("readInt");

        Object data = obtain.invoke(null);
        Object reply = obtain.invoke(null);
        try {
            writeInterfaceToken.invoke(data, "com.oplus.screenmode.IOplusScreenMode");
            writeString.invoke(data, pkg);
            writeInt.invoke(data, fps);
            Method transact = binder.getClass().getMethod("transact",
                int.class, parcelCls, parcelCls, int.class);
            Object ok = transact.invoke(binder, 0x0c, data, reply, 0);
            readException.invoke(reply);
            Object res = readInt.invoke(reply);
            System.out.println("transact=" + ok + " result=" + res);
        } finally {
            recycle.invoke(data); recycle.invoke(reply);
        }
    }
}
