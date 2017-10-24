package org.nzbhydra.web;

import com.google.common.base.Strings;
import org.nzbhydra.config.MainConfig;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class Interceptor extends HandlerInterceptorAdapter {
    @Autowired
    private MainConfig mainConfig;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null) {
            ip = ip.split(",")[0];
        } else {
            ip = request.getRemoteAddr();
        }
        if (mainConfig.getLogging().isLogIpAddresses()) {
            MDC.put("IPADDRESS", ip);
        }
        if (mainConfig.getLogging().isLogUsername() && !Strings.isNullOrEmpty(request.getRemoteUser())) {
            MDC.put("USERNAME", request.getRemoteUser());
        }
        SessionStorage.IP.set(ip);
        SessionStorage.username.set(request.getRemoteUser());
        SessionStorage.userAgent.set(request.getHeader("User-Agent"));
        SessionStorage.requestUrl.set(request.getRequestURI());

        return true;
    }
}
