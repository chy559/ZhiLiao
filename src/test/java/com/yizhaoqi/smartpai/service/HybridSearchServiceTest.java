package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class HybridSearchServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private OrgTagCacheService orgTagCacheService;

    @InjectMocks
    private HybridSearchService hybridSearchService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void resolveSearchOwnerIdShouldReturnDatabaseIdWhenInputIsUsername() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertEquals("1", hybridSearchService.resolveSearchOwnerId("alice"));
    }

    @Test
    void resolveSearchOwnerIdShouldReturnDatabaseIdWhenInputIsNumericId() {
        User user = new User();
        user.setId(2L);
        user.setUsername("bob");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        assertEquals("2", hybridSearchService.resolveSearchOwnerId("2"));
    }
}
