package plus.wcj.jetbrains.plugins.java2flowchart;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class MethodCall {
    // 模拟“其他 bean”（同文件内，便于 PSI resolve）
    private final OtherBean otherBean = new OtherBean();

    public static void main(String[] args) {
        MethodCall s = new MethodCall();
        s.entry();
    }

    // ---------------------------
    // 入口：集中触发多种调用形式
    // ---------------------------
    public void entry() {
        // 1) 直接链式调用（methodcall1 -> 2 -> 3）
        methodcall1(true);

        // 2) this. 显式调用
        this.methodcall1(false);

        // 3) 重载方法调用
        overload(1);
        overload("x");
        overload(1, 2, 3);

        // 4) 静态方法调用（类名/直接）
        staticUtil();
        MethodCall.staticUtil();

        // 5) 条件 + 短路内的调用（测试 &&/|| 解析时的调用点）
        if (isPositive(1) && hasText("hi") || expensiveCheck()) {
            onConditionPassed();
        }

        // 6) 循环里调用
        loopCalls(3);

        // 7) try/catch/finally 里的调用
        tryCatchFinallyCalls("123");
        tryCatchFinallyCalls("x");

        // 8) 递归 & 互递归
        factorial(4);
        isEven(10);

        // 9) lambda / method reference 中的调用
        lambdaCalls();

        // 10) 接口默认方法调用（Callback.super）
        callbackEntry();

        // 11) 内部类调用外部类方法
        innerClassCalls();

        // 12) 匿名类调用外部类方法
        anonymousClassCalls();

        // 13) “其他 bean”调用（外部对象方法调用）
        callOtherBean();

        // 14) 泛型方法调用
        String r = genericEcho("hello");
        useResult(r);
    }

    // ---------------------------
    // A) 基础：链式调用
    // ---------------------------
    public void methodcall1(boolean b) {
        methodcall2(b);
    }

    public void methodcall2(boolean b) {
        methodcall3(b);
    }

    public void methodcall3(boolean b) {
        if (b) {
            privateHelper("b=true");
        } else {
            this.privateHelper("b=false"); // this. 显式
        }
    }

    private void privateHelper(String msg) {
        // 再调用一层
        log(msg);
    }

    private void log(String msg) {
        // 空实现：仅用于测试调用关系
    }

    // ---------------------------
    // B) 重载/可变参数
    // ---------------------------
    public int overload(int x) {
        return overload(x, x + 1, x + 2);
    }

    public String overload(String s) {
        return concat("prefix-", s);
    }

    public int overload(int... values) {
        return Arrays.stream(values).sum();
    }

    private String concat(String a, String b) {
        return a + b;
    }

    // ---------------------------
    // C) 静态调用
    // ---------------------------
    public static void staticUtil() {
        // 静态方法里调用另一个静态方法
        staticHelper();
    }

    private static void staticHelper() {
        // empty
    }

    // ---------------------------
    // D) 条件调用点
    // ---------------------------
    private boolean isPositive(int x) {
        return x > 0;
    }

    private boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    private boolean expensiveCheck() {
        // 用于测试短路：可能不执行
        return methodReturningBool();
    }

    private boolean methodReturningBool() {
        return true;
    }

    private void onConditionPassed() {
        log("condition passed");
    }

    // ---------------------------
    // E) 循环里的调用
    // ---------------------------
    private void loopCalls(int n) {
        for (int i = 0; i < n; i++) {
            perIteration(i);
            if (i % 2 == 0) continue;
            perOddIteration(i);
        }
    }

    private void perIteration(int i) {
        log("i=" + i);
    }

    private void perOddIteration(int i) {
        methodcall3(true);
    }

    // ---------------------------
    // F) try/catch/finally 的调用
    // ---------------------------
    private void tryCatchFinallyCalls(String text) {
        beforeTry(text);
        try {
            int v = parseInt(text);
            onParsed(v);
        } catch (NumberFormatException ex) {
            onParseError(ex);
        } finally {
            cleanup(text);
        }
    }

    private void beforeTry(String text) {
        log("beforeTry:" + text);
    }

    private int parseInt(String text) {
        return Integer.parseInt(text);
    }

    private void onParsed(int v) {
        log("parsed:" + v);
    }

    private void onParseError(Exception ex) {
        log("error:" + ex.getClass().getSimpleName());
    }

    private void cleanup(String text) {
        log("cleanup:" + text);
    }

    // ---------------------------
    // G) 递归 & 互递归
    // ---------------------------
    public int factorial(int n) {
        if (n <= 1) return 1;
        return n * factorial(n - 1); // 直接递归
    }

    public boolean isEven(int n) {
        if (n == 0) return true;
        return isOdd(n - 1); // 互递归
    }

    public boolean isOdd(int n) {
        if (n == 0) return false;
        return isEven(n - 1);
    }

    // ---------------------------
    // H) lambda / method reference（注意：调用发生在 lambda 体内）
    // ---------------------------
    private void lambdaCalls() {
        List<String> list = List.of("a", "", "b");

        // lambda 里调用当前类方法
        list.forEach(s -> handleItem(s));

        // method reference 指向当前类方法
        Consumer<Boolean> c = this::methodcall3;
        c.accept(true);
    }

    private void handleItem(String s) {
        if (s == null || s.isEmpty()) {
            onEmptyItem();
            return;
        }
        onNonEmptyItem(s);
    }

    private void onEmptyItem() {
        log("empty");
    }

    private void onNonEmptyItem(String s) {
        log("item:" + s);
    }

    // ---------------------------
    // I) 接口默认方法 + Callback.super 调用
    // ---------------------------
    interface Callback {
        void onCall(String msg);

        default void defaultEntry() {
            // 默认方法内部调用接口方法（实现类会实现 onCall）
            onCall("from defaultEntry");
        }
    }

    class CallbackImpl implements Callback {
        @Override
        public void onCall(String msg) {
            // 实现里再调用外部类方法
            MethodCall.this.log("CallbackImpl:" + msg);
        }

        public void callDefaultViaSuper() {
            // Java 语法：InterfaceName.super.defaultMethod()
            Callback.super.defaultEntry();
        }
    }

    private void callbackEntry() {
        CallbackImpl impl = new CallbackImpl();
        impl.callDefaultViaSuper();
    }

    // ---------------------------
    // J) 内部类调用外部类方法
    // ---------------------------
    private void innerClassCalls() {
        Inner inner = new Inner();
        inner.run();
    }

    class Inner {
        void run() {
            // 内部类调用外部类方法
            MethodCall.this.methodcall2(true);
        }
    }

    // ---------------------------
    // K) 匿名类调用外部类方法
    // ---------------------------
    private void anonymousClassCalls() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                // 匿名类里调用外部类方法
                methodcall1(true);
            }
        };
        r.run();
    }

    // ---------------------------
    // L) “其他 bean”调用（外部对象方法调用）
    // ---------------------------
    private void callOtherBean() {
        String v = otherBean.externalMethod("x");
        useResult(v);
    }

    private void useResult(String v) {
        log("useResult:" + v);
    }

    static class OtherBean {
        String externalMethod(String in) {
            return helper(in);
        }

        private String helper(String in) {
            return "OtherBean:" + in;
        }
    }

    // ---------------------------
    // M) 泛型方法调用
    // ---------------------------
    public <T> T genericEcho(T v) {
        return v;
    }
}
