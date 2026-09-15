package pulse_api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The single owner's email, straight from the JWT subject. */
@Component
public class CurrentUser {

    public String email() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : String.valueOf(auth.getPrincipal());
    }
}
