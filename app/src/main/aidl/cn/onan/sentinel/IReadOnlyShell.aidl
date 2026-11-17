package cn.onan.sentinel;
interface IReadOnlyShell {
    String processes(String target) = 0;
    void destroy() = 16777114;
}
