package io.github.erdsgfc.postgresql;

import io.github.erdsgfc.postgresql.entity.SysAdmin;
import io.github.erdsgfc.postgresql.repository.SysAdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@SpringBootTest
public class JforgeTest {

    @Autowired
    SysAdminRepository sysAdminRepository;

    @Test
    @Transactional
    @Rollback(false)
    void baseRepTest() {
        SysAdmin sysAdmin = sysAdminRepository.findById(2068945292706926592L);
        if (sysAdmin != null) {
            sysAdmin.adminMobile("1787899999").adminPassword("123456");
            sysAdminRepository.update(sysAdmin);
        }

    }
}
