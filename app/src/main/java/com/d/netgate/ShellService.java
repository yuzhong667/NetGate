package com.d.netgate;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Shizuku UserService，运行身份为 shell。
 *
 * 关键：它不是 android.app.Service！Shizuku 的 starter 在独立 app_process 进程里
 * 反射实例化这个类，然后直接 (IBinder) 强转，再把 binder 交回服务端。
 * 因此必须直接继承 AIDL 生成的 IUserService.Stub，并提供无参或 (Context) 构造。
 *
 * 继承 Service 会导致 ClassCastException：进程在实例化后立刻死亡，异常只发生在
 * 服务端 shell 进程里，宿主 App 什么都看不到，onServiceConnected 永远不回调。
 * 这就是之前"强停永不回调、调整 daemon / 超时 / 事务码全无效"的真正原因。
 *
 * 注意：AndroidManifest 里不要再声明这个类，它不是四大组件。
 */
public class ShellService extends IUserService.Stub {

    public ShellService() {
    }

    // Shizuku 若拿到 package context，会优先用这个构造
    public ShellService(Context context) {
    }

    @Override
    public void destroy() {
        // Shizuku 解绑 / 服务端要求退出时调用。
        // 放到独立线程里稍作延迟再退出，避免同步阻塞 binder 事务返回。
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "netgate-svc-exit");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public String exec(String command) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            // stderr 已合并进 stdout，只需读一条管道，避免两边互相阻塞
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            drain(p.getInputStream(), buf);
            p.waitFor();
            return buf.toString("UTF-8");
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (Throwable ignored) {
        }
    }
}
