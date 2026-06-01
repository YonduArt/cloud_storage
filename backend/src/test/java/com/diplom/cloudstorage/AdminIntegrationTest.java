package com.diplom.cloudstorage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.repository.AppUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository userRepository;

    @Test
    void shouldAllowAdminToManageUsersAndRejectRegularUser() throws Exception {
        RegisteredUser admin = register("admin_user");
        RegisteredUser regular = register("regular_user");
        userRepository.findById(admin.id()).ifPresent(user -> {
            user.setRole(AppUser.Role.ROLE_ADMIN);
            userRepository.save(user);
        });

        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + regular.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        mockMvc.perform(patch("/api/admin/users/{id}", regular.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false,
                                  "storageQuotaBytes": 2147483648
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.storageQuotaBytes").value(2147483648L));
    }

    private RegisteredUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s_%s",
                                  "email":"%s-%s@example.com",
                                  "password":"123456",
                                  "displayName":"Admin Test User"
                                }
                                """.formatted(prefix, suffix, prefix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode user = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("user");
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
        return new RegisteredUser(user.path("id").asLong(), token);
    }

    private record RegisteredUser(Long id, String token) {
    }
}
