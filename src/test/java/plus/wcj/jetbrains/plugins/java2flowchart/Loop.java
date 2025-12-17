package plus.wcj.jetbrains.plugins.java2flowchart;


import java.io.BufferedReader;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;


public class Loop {
    public void while1() {
        while (true) {
            System.out.println("hello world");
        }
    }

    public void while2() {
        int x = 10;
        while (x < 20) {
            System.out.println("hello world" + x++);
        }
    }

    public void while3() {
        do {
            System.out.println("hello world");
        } while (true);
    }


    public void while4() {
        int x = 10;
        do {
            System.out.println("hello world" + x++);
        } while (x < 20);
    }

    public void while5() {
        int x = 10;
        do {
            if (x == 15) {
                throw new RuntimeException();
            }
            System.out.println("hello world" + x++);
        } while (x < 20);
    }

    public void while6() {
        int x = 10;
        do {
            if (x == 15) {
                return;
            }
            System.out.println("hello world" + x++);
        } while (x < 20);
    }


    public void for1() {
        for (; ; ) {
        }
    }

    public void for2() {
        for (; ; ) {
            System.out.println();
        }
    }

    public void for3() {
        for (int i = 0; i < 100; i++) {
            System.out.println(i);
        }
    }

    public void for4() {
        int[] numbers = {10, 20, 30, 40, 50};
        for (int i : numbers) {
            System.out.print(i);
        }
    }

    public void break1() {
        for (int i = 0; i < 100; i++) {
            if (i == 50) {
                break;
            }
        }
    }


    public void break2() {
        int x = 10;
        while (x < 20) {
            if (x == 50) {
                break;
            }
            x++;
        }
    }


    public void continue1() {
        for (int i = 0; i < 100; i++) {
            if (i == 50) {
                continue;
            }
            System.out.println(i);
        }
    }


    public void continue2() {
        int x = 10;
        while (x < 20) {
            if (x == 50) {
                return;
            }
            x++;
        }
    }

    public void continue3() {
        int x = 10;
        while (x < 20) {
            if (x == 50) {
                throw new RuntimeException();
            }
            x++;
        }
    }


    // ---------- 0) 基础 while / do-while / for / foreach ----------

    void whileLoop(int n) {
        int i = 0;
        while (i < n) {
            i++;
        }
    }

    void doWhileLoop(int n) {
        int i = 0;
        do {
            i++;
        } while (i < n);
    }

    void forLoop(int n) {
        for (int i = 0; i < n; i++) {
            // body
        }
    }

    void forEachArray(int[] arr) {
        for (int x : arr) {
            // body
        }
    }

    void forEachIterable(List<String> list) {
        for (String s : list) {
            // body
        }
    }

    // ---------- 1) 无限循环与提前退出（break / return / throw） ----------

    void infiniteBreak(int n) {
        int i = 0;
        while (true) {
            if (i++ >= n) break;
        }
    }

    int infiniteReturn(int n) {
        for (; ; ) {
            if (n <= 0) return 0;
            n--;
        }
    }

    void infiniteThrow() {
        while (true) {
            throw new RuntimeException("stop");
        }
    }

    // ---------- 2) continue / break 组合 ----------

    int sumPositiveSkipZero(int[] a) {
        int sum = 0;
        for (int x : a) {
            if (x == 0) continue;
            if (x < 0) break;
            sum += x;
        }
        return sum;
    }

    void nestedBreakContinue(int n) {
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) continue;
            for (int j = 0; j < n; j++) {
                if (j == 3) break;
            }
        }
    }

    // ---------- 3) Label 语法（break/continue outer） ----------

    void labeledBreak(int n) {
        outer:
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i + j > 10) break outer;
            }
        }
    }

    void labeledContinue(int n) {
        outer:
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (j == 2) continue outer;
            }
        }
    }

    // ---------- 4) for 变体：多变量、空 init/cond/update、复杂 update ----------

    void multiInitMultiUpdate() {
        for (int i = 0, j = 10; i < j; i++, j--) {
            // body
        }
    }

    void emptyParts(int n) {
        int i = 0;
        for (; i < n; ) {
            i++;
        }
    }

    void complexUpdate(List<Integer> list) {
        for (int i = 0; i < list.size(); i = i + 2) {
            // body
        }
    }

    void updateCalls(int n) {
        for (int i = 0; i < n; i += step(i)) {
            // body
        }
    }

    int step(int i) {
        return i == 0 ? 1 : 2;
    }

    // ---------- 5) while 条件中赋值（readLine 模式） ----------

    void readLoop(BufferedReader br) throws Exception {
        String line;
        while ((line = br.readLine()) != null) {
            // process line
            if (line.isEmpty()) continue;
            if (line.startsWith("stop")) break;
        }
    }

    // ---------- 6) Iterator / ListIterator ----------

    void iteratorLoop(List<String> list) {
        for (Iterator<String> it = list.iterator(); it.hasNext(); ) {
            String s = it.next();
            if (s == null) break;
        }
    }

    void listIteratorLoop(List<String> list) {
        ListIterator<String> it = list.listIterator();
        while (it.hasNext()) {
            String s = it.next();
            if (s != null && s.startsWith("x")) {
                // do something
            }
        }
    }

    // ---------- 7) 修改集合：iterator.remove / removeIf ----------

    void iteratorRemove(List<Integer> list) {
        for (Iterator<Integer> it = list.iterator(); it.hasNext(); ) {
            Integer x = it.next();
            if (x != null && x < 0) it.remove();
        }
    }

    void removeIf(List<Integer> list) {
        list.removeIf(x -> x != null && x < 0);
    }

    // ---------- 8) 循环 + try/catch/finally ----------

    void loopWithTry(List<String> list) {
        for (String s : list) {
            try {
                if (s == null) continue;
                Integer.parseInt(s);
            } catch (NumberFormatException ex) {
                // handle
            } finally {
                // cleanup
            }
        }
    }

    // ---------- 9) switch 在循环中：break/continue 语义混合 ----------

    void loopSwitch(int[] a) {
        for (int x : a) {
            switch (x) {
                case 0:
                    continue; // continue loop
                case 1:
                    break;    // break switch only
                default:
                    // body
            }
            // after switch (still in loop)
        }
    }

    // ---------- 10) Stream / lambda 的“隐式循环” ----------

    int sumStream(List<Integer> list) {
        return list.stream()
                .filter(x -> x != null && x > 0)
                .mapToInt(x -> x)
                .sum();
    }

    void forEachStream(List<String> list) {
        list.forEach(s -> {
            if (s == null) return;         // lambda return
            if (s.isEmpty()) return;
            System.out.println(s);
        });
    }

    // ---------- 11) 并行/异步风格的“循环” ----------

    void submitLoop(ExecutorService es, int n) {
        for (int i = 0; i < n; i++) {
            int x = i;
            es.submit(() -> System.out.println(x));
        }
    }

    void completableLoop(List<Integer> list) {
        list.forEach(x ->
                CompletableFuture.runAsync(() -> System.out.println(x))
        );
    }

    // ---------- 12) 递归作为“循环替代” ----------

    int factorial(int n) {
        if (n <= 1) return 1;
        return n * factorial(n - 1);
    }

}


