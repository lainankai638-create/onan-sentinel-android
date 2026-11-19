package cn.onan.sentinel;
interface IReadOnlyProbe {
    // No command/string/path argument crosses this interface. Both integers are allowlisted.
    String sample(int kind, int target) = 0;
    String snapshot() = 1;
    // No PID/path/command input: service selects at most four visible representatives itself.
    String resources() = 2;
    void destroy() = 16777114;
}
