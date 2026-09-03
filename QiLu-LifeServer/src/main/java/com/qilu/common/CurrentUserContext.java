package com.qilu.common;

import com.qilu.dto.UserDTO;
import com.qilu.utils.UserHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
public class CurrentUserContext {

    public UserDTO currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UserDTO) {
                return (UserDTO) principal;
            }
        }
        return UserHolder.getUser();
    }

    public Long currentUserId() {
        UserDTO user = currentUser();
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("Please login first");
        }
        return user.getId();
    }

    public String currentRole() {
        UserDTO user = currentUser();
        if (user != null && user.getRole() != null) {
            return user.getRole();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities == null || authorities.isEmpty()) {
            return null;
        }
        String authority = authorities.iterator().next().getAuthority();
        return authority == null ? null : authority.replace("ROLE_", "").toLowerCase();
    }

    public boolean isAdmin() {
        return "admin".equals(currentRole());
    }

    public boolean isManager() {
        return "manager".equals(currentRole());
    }
}
