# Phase 8 minimal checkout UI — static files only, no build step (no
# framework/bundler in scope for a "minimal" UI per the phase's own
# scope decision). nginxinc/nginx-unprivileged, not plain nginx: runs
# as a non-root user out of the box on port 8080 (matches every backend
# service's non-root convention here) without a custom useradd dance.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY src/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
