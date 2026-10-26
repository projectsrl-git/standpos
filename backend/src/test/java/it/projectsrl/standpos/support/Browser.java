package it.projectsrl.standpos.support;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A minimal browser over MockMvc: keeps the cookies the server sets (session, device, XSRF-TOKEN) and sends the
 * CSRF header the way the SPA does, so tests exercise the real session and CSRF flow.
 */
public class Browser {

    private final MockMvc mvc;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private boolean sendCsrf = true;

    Browser(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Browser withoutCsrfHeader() {
        sendCsrf = false;
        return this;
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public Browser withCookie(String name, String value) {
        cookies.put(name, value);
        return this;
    }

    public void dropCookie(String name) {
        cookies.remove(name);
    }

    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        if (sendCsrf && !cookies.containsKey("XSRF-TOKEN")) {
            store(mvc.perform(withCookies(MockMvcRequestBuilders.get("/api/v1/auth/csrf"))).andReturn());
        }
        MockHttpServletRequestBuilder prepared = withCookies(request);
        if (sendCsrf && cookies.containsKey("XSRF-TOKEN")) {
            prepared.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        ResultActions actions = mvc.perform(prepared);
        store(actions.andReturn());
        return actions;
    }

    public ResultActions postJson(String url, String json) throws Exception {
        return perform(MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public ResultActions get(String url) throws Exception {
        return perform(MockMvcRequestBuilders.get(url));
    }

    private MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request) {
        if (!cookies.isEmpty()) {
            request.cookie(cookies.entrySet().stream()
                    .map(e -> new Cookie(e.getKey(), e.getValue()))
                    .toArray(Cookie[]::new));
        }
        return request;
    }

    private void store(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            String pair = header.split(";", 2)[0];
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            boolean expired = header.toLowerCase().contains("max-age=0");
            if (expired || value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }
}
