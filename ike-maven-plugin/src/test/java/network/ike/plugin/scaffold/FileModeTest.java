package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileModeTest {

    @Test
    void nullAndBlankResolveToDefault() {
        assertThat(FileMode.fromManifest(null))
                .isEqualTo(FileMode.DEFAULT);
        assertThat(FileMode.fromManifest("   "))
                .isEqualTo(FileMode.DEFAULT);
        assertThat(FileMode.fromManifest(""))
                .isEqualTo(FileMode.DEFAULT);
    }

    @Test
    void namedTokensResolve() {
        assertThat(FileMode.fromManifest("default"))
                .isEqualTo(FileMode.DEFAULT);
        assertThat(FileMode.fromManifest("executable"))
                .isEqualTo(FileMode.EXECUTABLE);
        assertThat(FileMode.fromManifest("private"))
                .isEqualTo(FileMode.PRIVATE);
    }

    @Test
    void tokenMatchIsCaseInsensitiveAndTrimmed() {
        assertThat(FileMode.fromManifest("  Executable "))
                .isEqualTo(FileMode.EXECUTABLE);
        assertThat(FileMode.fromManifest("PRIVATE"))
                .isEqualTo(FileMode.PRIVATE);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> FileMode.fromManifest("0755"))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("unknown file mode '0755'");
    }

    @Test
    void octalBitsMatchEachMode() {
        assertThat(FileMode.DEFAULT.octal()).isEqualTo(0644);
        assertThat(FileMode.EXECUTABLE.octal()).isEqualTo(0755);
        assertThat(FileMode.PRIVATE.octal()).isEqualTo(0600);
    }

    @Test
    void isExplicitOnlyForNonDefault() {
        assertThat(FileMode.DEFAULT.isExplicit()).isFalse();
        assertThat(FileMode.EXECUTABLE.isExplicit()).isTrue();
        assertThat(FileMode.PRIVATE.isExplicit()).isTrue();
    }

    @Test
    void posixPermissionsRenderAsExpectedRwxStrings() {
        assertThat(rwx(FileMode.DEFAULT)).isEqualTo("rw-r--r--");
        assertThat(rwx(FileMode.EXECUTABLE)).isEqualTo("rwxr-xr-x");
        assertThat(rwx(FileMode.PRIVATE)).isEqualTo("rw-------");
    }

    @Test
    void executablePermissionSetContainsOwnerExecute() {
        assertThat(FileMode.EXECUTABLE.toPosixPermissions())
                .contains(PosixFilePermission.OWNER_EXECUTE);
        assertThat(FileMode.PRIVATE.toPosixPermissions())
                .doesNotContain(PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ);
    }

    private static String rwx(FileMode mode) {
        return PosixFilePermissions.toString(mode.toPosixPermissions());
    }
}
