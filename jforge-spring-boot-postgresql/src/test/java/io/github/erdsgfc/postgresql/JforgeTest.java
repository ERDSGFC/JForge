package io.github.erdsgfc.postgresql;

import io.github.erdsgfc.postgresql.entity.SysAdmin;
import io.github.erdsgfc.postgresql.repository.SysAdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
public class JforgeTest {

    @Autowired
    SysAdminRepository sysAdminRepository;

    @Test
    void baseRepTest() {
        List<SysAdmin> all = sysAdminRepository.findAll();
        all.forEach(System.out::println);
    }
}
