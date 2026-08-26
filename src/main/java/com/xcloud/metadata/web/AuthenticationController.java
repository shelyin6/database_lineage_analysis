package com.xcloud.metadata.web;

import com.xcloud.metadata.security.AuthUser;
import com.xcloud.metadata.security.AuthenticationService;
import com.xcloud.metadata.security.AuthenticationSession;
import com.xcloud.metadata.service.AnnotationStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final AuthenticationService authenticationService;
    private final AnnotationStore annotationStore;

    public AuthenticationController(AuthenticationService authenticationService, AnnotationStore annotationStore) {
        this.authenticationService = authenticationService;
        this.annotationStore = annotationStore;
    }

    @PostMapping("/login")
    public UserResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String requestedUsername = request == null ? "" : request.username();
        AuthUser user = authenticationService.authenticate(requestedUsername, request == null ? null : request.password());
        String ipAddress = clientAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        if (user == null) {
            annotationStore.recordLogin(new AnnotationStore.LoginLog(
                    0, authenticationService.normalizeUsername(requestedUsername), "", false,
                    "用户名或密码错误", ipAddress, userAgent, Instant.now().toString()
            ));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        HttpSession previous = httpRequest.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute(AuthenticationSession.USER_ATTRIBUTE, user);
        annotationStore.recordLogin(new AnnotationStore.LoginLog(
                0, user.username(), user.displayName(), true, "", ipAddress, userAgent, Instant.now().toString()
        ));
        return UserResponse.from(user);
    }

    @GetMapping("/me")
    public UserResponse currentUser(HttpServletRequest request) {
        return UserResponse.from(requireUser(request));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @GetMapping("/login-logs")
    public List<AnnotationStore.LoginLog> loginLogs(HttpServletRequest request) {
        if (!requireUser(request).isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看登录日志");
        }
        return annotationStore.loginLogs(200);
    }

    private static AuthUser requireUser(HttpServletRequest request) {
        AuthUser user = AuthenticationSession.current(request.getSession(false));
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return user;
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserResponse(String username, String displayName, String role, boolean admin) {
        private static UserResponse from(AuthUser user) {
            return new UserResponse(user.username(), user.displayName(), user.role(), user.isAdmin());
        }
    }
}
