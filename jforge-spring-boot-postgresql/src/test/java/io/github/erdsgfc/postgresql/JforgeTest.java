package io.github.erdsgfc.postgresql;

import io.github.erdsgfc.postgresql.entity.SysAdmin;
import io.github.erdsgfc.postgresql.repository.SysAdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
public class JforgeTest {

    @Autowired
    SysAdminRepository sysAdminRepository;

    @Test
    @Transactional
    @Rollback(false)
    void baseRepTest() {
    }

    /** @NonNull 契约参数:null 快速失败(requireNonNull),而非静默 NPE 于绑定处。 */
    @Test
    void nullParameterFailsFast() {
        assertThrows(NullPointerException.class, () -> sysAdminRepository.update(null));
    }

    @Test
    void updateAdminStatus() {
        long l = sysAdminRepository.updateAdminStatus(1, new long[]{1L, 2L});
    }
}
