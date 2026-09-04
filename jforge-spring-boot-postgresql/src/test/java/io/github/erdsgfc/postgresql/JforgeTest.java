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
        // save → findById → update → findAll 全链路(自包含,不依赖固定 id)。
        long adminId = ThreadLocalRandom.current().nextLong(1_000_000);
        SysAdmin created = sysAdminRepository.save(sysAdminRepository.createEntity()
                .adminId(adminId).adminName("tester").adminMobile("1787899999").adminPassword("123456"));
        assertNotNull(created);

        SysAdmin found = sysAdminRepository.findById(adminId);
        assertNotNull(found);
        found.adminMobile("1787000000");
        assertEquals(true, sysAdminRepository.update(found));

        List<SysAdmin> all = sysAdminRepository.findAll();
        assertEquals(true, all.stream().anyMatch(a -> a.adminId() == adminId));
    }

    /** @NonNull 契约参数:null 快速失败(requireNonNull),而非静默 NPE 于绑定处。 */
    @Test
    void nullParameterFailsFast() {
        assertThrows(NullPointerException.class, () -> sysAdminRepository.update(null));
    }
}
