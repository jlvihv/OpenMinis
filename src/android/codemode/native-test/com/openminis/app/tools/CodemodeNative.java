package com.openminis.app.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal smoke tests against the production JNI bridge and QuickJS engine. */
public final class CodemodeNative {
    static { System.loadLibrary("codemode_jni"); }
    private static byte[] prelude;
    private final List<String> events = new ArrayList<>();
    private CountDownLatch started;
    public void onEvent(byte[] event) {
        String json = new String(event, StandardCharsets.UTF_8);
        events.add(json);
        if (started != null && json.contains("\"type\":\"output\"")) started.countDown();
    }
    public native long create(byte[] prelude, byte[] tools, byte[] globals, byte[] store);
    public native void start(long handle, byte[] code);
    public native void settle(long handle, int id, boolean ok, byte[] payload);
    public native void interrupt(long handle);
    public native void release(long handle);
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private long create() { return create(prelude,
        bytes("[{\"name\":\"echo\",\"jsName\":\"echo\",\"description\":\"Echo\"}]"),
        bytes("[]"), bytes("{\"saved\":\"{\\\"value\\\":7}\"}")); }
    private void startScript(long handle, String code) { start(handle, bytes("(async (tools, console) => {" + code + "\n})")); }
    private static void check(boolean test, String message) { if (!test) throw new AssertionError(message); }
    private String last() { return events.isEmpty() ? "" : events.get(events.size() - 1); }
    private void success() { check(last().contains("\"ok\":true"), events.toString()); }
    private static CodemodeNative run(String code) {
        CodemodeNative vm = new CodemodeNative(); long handle = vm.create();
        try { vm.startScript(handle, code); return vm; } finally { vm.release(handle); }
    }
    private static int count;
    private static void test(String name, Checked action) throws Exception {
        action.run(); count++; System.out.println("PASS " + name);
    }
    private interface Checked { void run() throws Exception; }

    private static void interruptLoop(String loop) throws Exception {
        CodemodeNative vm = new CodemodeNative(); vm.started = new CountDownLatch(1);
        AtomicLong handle = new AtomicLong(); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try { handle.set(vm.create()); vm.startScript(handle.get(), "text('started'); " + loop); }
            catch (Throwable e) { error.set(e); }
            finally { if (handle.get() != 0) vm.release(handle.get()); }
        });
        owner.setDaemon(true); owner.start();
        check(vm.started.await(5, TimeUnit.SECONDS), "VM failed to start");
        vm.interrupt(handle.get()); owner.join(5000);
        check(!owner.isAlive(), "VM did not stop"); check(error.get() == null, "JNI exception: " + error.get());
    }
    public static void main(String[] args) throws Exception {
        prelude = Files.readAllBytes(Path.of(args[0]));
        test("UTF-8, store and no ambient host bindings", () -> {
            CodemodeNative vm = run("if (![typeof std, typeof os, typeof fetch, typeof bridge, typeof java].every(t => t === 'undefined')) throw Error('host exposed'); console.log('你好🌍'); store('a', 1); return load('saved').value");
            vm.success(); check(vm.events.toString().contains("你好🌍"), vm.events.toString());
            check(vm.last().contains("\"value\":\"7\"") && vm.last().contains("writes"), vm.last());
        });
        test("parallel promises resolve structured data and reject errors", () -> {
            CodemodeNative vm = new CodemodeNative(); long handle = vm.create();
            try {
                vm.startScript(handle, "const r = await Promise.all([tools.echo({}), tools.echo({}).catch(e => e.message)]); return r[0].exit_code === 7 && r[1] === 'denied'");
                check(vm.events.size() == 2, vm.events.toString());
                vm.settle(handle, 2, false, bytes("denied"));
                vm.settle(handle, 1, true, bytes("{\"output\":\"ok\",\"exit_code\":7}"));
                vm.success(); check(vm.last().contains("\"value\":\"true\""), vm.last());
            } finally { vm.release(handle); }
        });
        test("native memory limit fails without crashing host", () -> {
            CodemodeNative vm = run("const a=[]; while(true) a.push(new Uint8Array(1024*1024))");
            check(vm.last().contains("\"ok\":false") || vm.last().contains("\"type\":\"crash\""), vm.events.toString());
            run("return 'alive'").success();
        });
        test("interrupt busy script", () -> interruptLoop("while (true) {}"));
        test("interrupt busy Promise microtasks", () -> interruptLoop("while (true) await Promise.resolve()"));
        System.out.println(count + " native JNI tests passed");
    }
}
