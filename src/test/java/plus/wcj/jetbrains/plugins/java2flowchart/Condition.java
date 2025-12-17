package plus.wcj.jetbrains.plugins.java2flowchart;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class Condition {

    public void if1() {
        if (true) {
        }
        System.out.println();
    }

    public void if2() {
        if (true) {
            System.out.println("true.");
        }
    }

    public void if3() {
        if (true) {
            System.out.println("true.");
        }
        System.out.println("done.");
    }

    public void if4() {
        if (returnTrue() || returnFalse()) {
        }
    }

    public void if5() {
        if (true) {
            System.out.println("true.");
        } else {
            System.out.println(false);
        }
        System.out.println("done.");
    }

    public boolean returnTrue() {
        return true;
    }

    public boolean returnFalse() {
        return false;
    }

    public void ifelse1() {
        if (true) {
            System.out.println("true.");
        } else {
            System.out.println("false.");
        }
        System.out.println("done.");
    }

    public void ifelse2() {
        if (true) {
            System.out.println("true.");
        } else if (false) {
            System.out.println("false.");
        }
        System.out.println("done.");
    }

    public void ifelse3() {
        if (true) {
            System.out.println("true.");
        } else if (true) {
            System.out.println("false.");
        } else if (true) {
            System.out.println("false.");
        }
        System.out.println("done.");
    }

    public void ifelse4() {
        if (true) {
            System.out.println("true.");
        } else if (true) {
            System.out.println("false.");
        } else if (true) {
            System.out.println("false.");
        } else {
            System.out.println("false.");
        }
        System.out.println("done.");
    }

    public void ifelse5() {
        if (true) {
            System.out.println("true.");
            return;
        } else if (true) {
            System.out.println("false.");
        } else if (true) {
            System.out.println("false.");
        } else {
            System.out.println("false.");
        }
        System.out.println("done.");
    }


    public void switch1(String b) {
        switch (b) {
        }
    }

    public void switch2(String b) {
        switch (b) {
            case "1":
                System.out.println();
        }
    }


    public void switch3(String b) {
        switch (b) {
            case "1":
                System.out.println(1);
            case "2":
                System.out.println(2);
            default:
                System.out.println("default");
        }
    }

    public void switch4(String b) {
        switch (b) {
            default:
                System.out.println("default");
        }
    }

    public void switch5(String b) {
        switch (b) {
            case "1":
                System.out.println(1);
                break;
            case "2":
                System.out.println(2);
                break;
            default:
                System.out.println("default");
                break;
        }
    }

    public void switch6(String b) {
        switch (b) {
            case "1":
                return;
        }
    }

    public void switch7(String b) {
        switch (b) {
            case "1":
                System.out.println(1);
                break;
            case "2":
                System.out.println(2);
                return;
            default:
                System.out.println("default");
                return;
        }
    }

    public void switch8(String b) {
        switch (b) {
            case "1":
                System.out.println(1);
                return;
            case "2":
                System.out.println(2);
        }
        System.out.println(2);
    }


    public void switch9(String b) {
        switch (b) {
            case "1":
                System.out.println(1);
                return;
            case "2":
                System.out.println(2);
                throw new RuntimeException();
            default:
                System.out.println("default");

        }
        System.out.println(2);
    }


    public void switch10(String b) {
        switch (b) {
            case "1":
                System.out.println(1);
            case "2":
                System.out.println(2);
                break;
            case "3":
                System.out.println(3);
                return;
            case "4":
                System.out.println(4);
                throw new RuntimeException();
            default:
                System.out.println("default");

        }
        System.out.println(2);
    }


    // ---------------------------
    // 0) 基础 if / else
    // ---------------------------

    int basicIfElse(int x) {
        if (x > 0) {
            return 1;
        } else {
            return -1;
        }
    }

    int elseIfChain(int score) {
        if (score >= 90) return 5;
        else if (score >= 80) return 4;
        else if (score >= 70) return 3;
        else if (score >= 60) return 2;
        else return 1;
    }

    String nestedIf(int a, int b) {
        if (a > 0) {
            if (b > 0) return "a>0 && b>0";
            else return "a>0 && b<=0";
        } else {
            if (b > 0) return "a<=0 && b>0";
            else return "a<=0 && b<=0";
        }
    }

    // ---------------------------
    // 1) 逻辑运算：短路 && || 与非短路 & |
    // ---------------------------

    boolean shortCircuitAnd(boolean a, boolean b) {
        // b 可能不会被计算（短路）
        return a && b;
    }

    boolean shortCircuitOr(boolean a, boolean b) {
        // b 可能不会被计算（短路）
        return a || b;
    }

    boolean nonShortCircuitAnd(boolean a, boolean b) {
        // 注意：& 对 boolean 是“非短路与”，两边都会计算（用于测试 PSI/CFG 很好）
        return a & b;
    }

    boolean nonShortCircuitOr(boolean a, boolean b) {
        // 注意：| 对 boolean 是“非短路或”
        return a | b;
    }

    // ---------------------------
    // 2) null 条件、equals、字符串/集合常见条件
    // ---------------------------

    boolean nullChecks(String s) {
        if (s == null) return false;
        if (s.isEmpty()) return false;
        return true;
    }

    boolean safeEquals(String a, String b) {
        // 避免 NPE 的 equals 写法
        return Objects.equals(a, b);
    }

    boolean collectionConditions(List<Integer> list) {
        if (list == null || list.isEmpty()) return false;
        return list.size() > 3 && list.contains(2);
    }

    boolean mapConditions(Map<String, Integer> map, String key) {
        return map.get(key) != null && map.get(key) > 10;
    }

    // ---------------------------
    // 3) 三元表达式（?:）及嵌套
    // ---------------------------

    String ternaryBasic(int x) {
        return x >= 0 ? "non-negative" : "negative";
    }

    String ternaryNested(int x) {
        return x > 0 ? "positive" : x == 0 ? "zero" : "negative";
    }

    String ternaryNested2(int x) {
        return x > 0 ? "positive" : (x == 0 ? "zero" : "negative");
    }

    String ternaryNested3(int x) {
        return  x > 0 ? "positive" : (x == 0 ? "zero" : (x == -1 ? "zero" : "negative"));
    }

    String ternaryNested4(int x) {
        String s =  x > 0 ? "positive" : (x == 0 ? "zero" : (x == -1 ? "zero" : "negative"));
        return s;
    }

    // ---------------------------
    // 4) instanceof（含模式匹配）
    // ---------------------------

    String instanceofClassic(Object obj) {
        if (obj instanceof String) {
            String s = (String) obj;
            return "String:" + s;
        }
        return "Other";
    }

    String instanceofPattern(Object obj) {
        // Java 16+：instanceof 模式匹配
        if (obj instanceof String s && !s.isBlank()) {
            return "String(nonBlank):" + s;
        } else if (obj instanceof Integer i && i > 0) {
            return "PositiveInt:" + i;
        }
        return "Other/Blank/NonPositive";
    }

    // ---------------------------
    // 5) assert（条件断言）
    // ---------------------------

    void assertCondition(int x) {
        // 运行时需启用 -ea 才生效，但语法/PSI 可解析
        assert x >= 0 : "x must be non-negative";
    }

    // ---------------------------
    // 6) switch 语句（statement）：fallthrough / break
    // ---------------------------

    String switchStatementClassic(int day) {
        String name;
        switch (day) {
            case 1:
                name = "Mon";
                break;
            case 2:
                name = "Tue";
                break;
            case 3:
            case 4:
                name = "Mid";
                break;
            default:
                name = "Other";
        }
        return name;
    }

    // ---------------------------
    // 7) switch 表达式（expression）：箭头、yield、多标签
    // ---------------------------

    int switchExpressionArrow(String op, int a, int b) {
        // Java 14+：switch expression
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*", "x" -> a * b;      // 多标签
            case "/" -> b == 0 ? 0 : a / b;
            default -> 0;
        };
    }

    String switchExpressionYield(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 400, 404 -> "CLIENT_ERROR";
            case 500 -> {
                // 代码块里用 yield 返回
                String msg = "SERVER_ERROR";
                yield msg;
            }
            default -> "UNKNOWN";
        };
    }

    String switchExpressionYield2(int code) {
        String data = switch (code) {
            case 200 -> "OK";
            case 400, 404 -> "CLIENT_ERROR";
            case 500 -> {
                // 代码块里用 yield 返回
                String msg = "SERVER_ERROR";
                yield msg;
            }
            default -> "UNKNOWN";
        };
        return data;
    }

    // ---------------------------
    // 8) Optional 的条件式处理（不是语法 if，但常用于条件分支）
    // ---------------------------

    String optionalIfPresentOrElse(Optional<String> opt) {
        final StringBuilder sb = new StringBuilder();
        opt.ifPresentOrElse(
                v -> sb.append("value=").append(v),
                () -> sb.append("empty")
        );
        return sb.toString();
    }

    // =========================================================
    // 9) Java 21：switch 模式匹配（用于 PSI 测试很关键）
    //    需要把项目 language level 设到 21
    // =========================================================

    sealed interface Shape permits Circle, Rect, Unknown {
    }

    record Circle(double r) implements Shape {
    }

    record Rect(double w, double h) implements Shape {
    }

    record Unknown(String reason) implements Shape {
    }

    String switchPatternMatching(Shape s) {
        // Java 21：switch pattern matching（含 case null）
        return switch (s) {
            case null -> "NULL";
            case Circle c -> "Circle r=" + c.r();
            case Rect r when r.w() > 0 && r.h() > 0 -> "Rect area=" + (r.w() * r.h()); // guard
            case Rect r -> "Rect invalid";
            case Unknown u -> "Unknown: " + u.reason();
        };
    }

    enum Gender {
        male, female
    }


    String switchPatternMatching_enum(Gender s) {
        return switch (s) {
            case male ->  "male";
            case female ->  "female";
        };
    }

    String switchPatternMatching_enum_(Gender s) {
        switch (s) {
            case male :
                return "male";
            case female :
                return "female";
        }
        return "UNKNOWN";
    }


}
