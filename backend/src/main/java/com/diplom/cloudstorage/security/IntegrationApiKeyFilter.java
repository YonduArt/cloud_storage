package com.diplom.cloudstorage.security;

import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.model.IntegrationClient;
import com.diplom.cloudstorage.service.IntegrationClientService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class IntegrationApiKeyFilter extends OncePerRequestFilter {

    private static final String INTEGRATION_PREFIX = "/api/integration/v1";
    public static final String REQ_ATTR_CLIENT_ID = "integrationClientId";
    public static final String REQ_ATTR_SCOPES = "integrationScopes";

    private final IntegrationClientService integrationClientService;

    public IntegrationApiKeyFilter(IntegrationClientService integrationClientService) {
        this.integrationClientService = integrationClientService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(INTEGRATION_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String apiKey = request.getHeader("X-Api-Key");
            try {
                IntegrationClient client = integrationClientService.requireEnabledByApiKey(apiKey);
                AppUser owner = client.getOwner();
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        owner.getUsername(),
                        null,
                        owner.getAuthorities()
                );
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
                request.setAttribute(REQ_ATTR_CLIENT_ID, client.getId());
                request.setAttribute(REQ_ATTR_SCOPES, integrationClientService.splitScopes(client.getScopes()));
            } catch (Exception ignored) {
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid or missing API key");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
