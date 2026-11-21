package com.civicsense.util;
import java.time.LocalDate;

public class IdGenerator {
    public static String nextComplaintId(long lastSeq) {
        return String.format("CS-%d-%05d", LocalDate.now().getYear(), lastSeq + 1);
    }
    public static String nextEmployeeCode(String deptCode, long seq) {
        return deptCode.toUpperCase() + String.format("%04d", seq);
    }
}
