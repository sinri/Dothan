package io.github.sinri.Dothan;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DothanCliTest {
    @Test
    void noDiagnosticOptionKeepsVerboseDisabled() throws Exception {
        CommandLine options = parse();

        assertFalse(Dothan.isVerbose(options));
        assertFalse(Dothan.usesDeprecatedDetailOption(options));
    }

    @Test
    void deprecatedDetailOptionStillEnablesVerboseDiagnostics() throws Exception {
        CommandLine options = parse("-d");

        assertTrue(Dothan.isVerbose(options));
        assertTrue(Dothan.usesDeprecatedDetailOption(options));
    }

    @Test
    void verboseOptionEnablesDiagnosticsWithoutDeprecationWarning() throws Exception {
        CommandLine options = parse("-v");

        assertTrue(Dothan.isVerbose(options));
        assertFalse(Dothan.usesDeprecatedDetailOption(options));
    }

    @Test
    void combinedDiagnosticOptionsRemainVerboseAndReportDeprecatedAlias() throws Exception {
        CommandLine options = parse("-d", "-v");

        assertTrue(Dothan.isVerbose(options));
        assertTrue(Dothan.usesDeprecatedDetailOption(options));
    }

    @Test
    void unknownOptionIsRejected() {
        assertThrows(ParseException.class, () -> parse("--unknown"));
    }

    private CommandLine parse(String... arguments) throws ParseException {
        return new DefaultParser().parse(Dothan.options(), arguments);
    }
}
