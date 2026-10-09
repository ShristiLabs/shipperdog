package com.shipperdog.api.device.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of the CSV export's cell serializer (issue #48, S11-03):
 * the RFC 4180 escaping the provisioning columns already carried, plus
 * the formula-injection neutralization the tester's review loop added —
 * a cell opening with =, +, -, @, or a tab would execute as a formula
 * when an admin opens the download in Excel or Sheets. The prefix is
 * applied before quoting, so it survives inside quoted fields, and a
 * legitimate hyphenated word never opens with the dash.
 */
class DeviceServiceCsvFieldTest {

    @Test
    void formulaOpenersArePrefixedWithTheNeutralApostrophe() {
        assertThat(DeviceService.csvField("=SUM(A1:A9)")).isEqualTo("'=SUM(A1:A9)");
        assertThat(DeviceService.csvField("+HYPERLINK(\"http://evil.example\",\"x\")"))
                .isEqualTo("\"'+HYPERLINK(\"\"http://evil.example\"\",\"\"x\"\")\"");
        assertThat(DeviceService.csvField("-2+cmd|' /C calc'!A0")).isEqualTo("'-2+cmd|' /C calc'!A0");
        assertThat(DeviceService.csvField("@cmd!A0")).isEqualTo("'@cmd!A0");
        // Registration trims text today, so a tab opener cannot reach the
        // export through the API; the serializer still neutralizes it for
        // any future write path.
        assertThat(DeviceService.csvField("\t=cmd")).isEqualTo("'\t=cmd");
    }

    @Test
    void thePrefixLandsInsideRfc4180QuotingWhenBothApply() {
        // The opener arms the formula and the comma arms the quoting: the
        // apostrophe must precede the formula text inside the quotes.
        assertThat(DeviceService.csvField("=SUM(1,2)")).isEqualTo("\"'=SUM(1,2)\"");
        assertThat(DeviceService.csvField("@x,\"y\"")).isEqualTo("\"'@x,\"\"y\"\"\"");
    }

    @Test
    void legitimateValuesPassThroughUntouched() {
        // A hyphenated word never opens with the dash: mid-string hyphens
        // stay literal.
        assertThat(DeviceService.csvField("iPad-Gen-7")).isEqualTo("iPad-Gen-7");
        assertThat(DeviceService.csvField("Pixel 9 Pro-xl")).isEqualTo("Pixel 9 Pro-xl");
        assertThat(DeviceService.csvField("18.2")).isEqualTo("18.2");
        assertThat(DeviceService.csvField("CSV-2")).isEqualTo("CSV-2");
        assertThat(DeviceService.csvField("a@tenant.example")).isEqualTo("a@tenant.example");
    }

    @Test
    void rfc4180EscapingItselfIsUnchanged() {
        assertThat(DeviceService.csvField("iPhone 15, Pro")).isEqualTo("\"iPhone 15, Pro\"");
        assertThat(DeviceService.csvField("CSV-\"1\"")).isEqualTo("\"CSV-\"\"1\"\"\"");
        assertThat(DeviceService.csvField(null)).isEmpty();
        assertThat(DeviceService.csvField("")).isEmpty();
    }
}
