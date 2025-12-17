package plus.wcj.jetbrains.plugins.java2flowchart.render;

public record RenderOptions(String direction) {
    public static RenderOptions topDown() {
        return new RenderOptions("TD");
    }
}
