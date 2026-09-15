import os
import re

from flask_appbuilder.security.manager import AUTH_REMOTE_USER

from superset_security_manager import PlatformRemoteUserSecurityManager

# ==============================================================================
# Database — fail closed; no hardcoded credentials
# ------------------------------------------------------------------------------
# Require SUPERSET_DATABASE_URI (compose .env / k8s Secret). Only postgresql+psycopg2
# is allowed. Schema name must be a safe PostgreSQL identifier; search_path is
# injected when missing so metadata never lands in public by accident.
# ==============================================================================
_REQUIRED_DB_PREFIX = "postgresql+psycopg2://"
_SCHEMA_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def _build_sqlalchemy_database_uri() -> str:
    uri = os.environ.get("SUPERSET_DATABASE_URI", "").strip()
    if not uri:
        raise RuntimeError(
            "SUPERSET_DATABASE_URI is required (inject via .env or k8s Secret; "
            "no default credentials)"
        )
    if not uri.startswith(_REQUIRED_DB_PREFIX):
        raise RuntimeError(
            "SUPERSET_DATABASE_URI must use the postgresql+psycopg2 driver "
            f"(got prefix {uri.split(':', 1)[0]!r})"
        )
    schema = os.environ.get("SUPERSET_DB_SCHEMA", "superset").strip() or "superset"
    if not _SCHEMA_NAME_RE.fullmatch(schema):
        raise RuntimeError(
            f"SUPERSET_DB_SCHEMA must be a safe PostgreSQL identifier, got {schema!r}"
        )
    if "search_path" not in uri:
        sep = "&" if "?" in uri else "?"
        uri = f"{uri}{sep}options=-csearch_path%3D{schema}"
    return uri


SQLALCHEMY_DATABASE_URI = _build_sqlalchemy_database_uri()

# ==============================================================================
# Secret key
# ------------------------------------------------------------------------------
# Must be supplied via env (compose .env / k8s Secret). No insecure literal
# fallback: a wrong/weak SECRET_KEY lets anyone forge guest tokens. Fail closed
# if it is missing rather than silently running with a default.
# (Superset's own default config also derives SECRET_KEY from SUPERSET_SECRET_KEY;
#  we set it explicitly so the source of truth is unambiguous.)
# ==============================================================================
SECRET_KEY = os.environ["SUPERSET_SECRET_KEY"]

# ==============================================================================
# Subpath hosting (single-FQDN + path routing)
# ------------------------------------------------------------------------------
# Superset 6.0 NATIVELY reads the SUPERSET_APP_ROOT env var (see superset/app.py)
# and sets APPLICATION_ROOT from it, mounting the whole app under that prefix —
# routes, static-asset URLs, and redirects all carry it. So to serve Superset at
# e.g. http://<host>/bi (same FQDN as /admin, /portal, /dev) we ONLY set that env
# var (compose/k8s); the edge forwards /bi/* unchanged (no prefix stripping).
# Here we merely enable ProxyFix so Superset honors the edge's X-Forwarded-Proto/
# Host when building absolute URLs (correct https scheme for guest-token/embedded
# links in prod). Do NOT set STATIC_ASSETS_PREFIX: APPLICATION_ROOT already
# prefixes assets, so adding it would double the prefix (/bi/bi/static).
# ==============================================================================
if os.getenv("SUPERSET_APP_ROOT", "").rstrip("/"):
    ENABLE_PROXY_FIX = True
    # NOTE on the top-left brand logo under a subpath: Superset 6.0 renders it from
    # Ant-Design THEME tokens whose defaults ignore APPLICATION_ROOT — brandLogoHref="/"
    # (logo click -> bare root) and brandLogoUrl="/static/…" (bare -> 404). The active
    # theme here is the legacy HSBC THEME_OVERRIDES (which can't carry those Ant tokens),
    # and a DB-stored theme can override config anyway — so this is fixed reliably at the
    # edge instead (nginx-edge.conf: bare "/" with a /bi referer -> /bi/superset/welcome/,
    # and the bare logo image path -> /bi/static/…). See SUPERSET_SSO_INTEGRATION.md §3.

# ==============================================================================
# Feature Flags
# ==============================================================================
FEATURE_FLAGS = {
    "EMBEDDED_SUPERSET": True,
    "ALERTS": True,
    # Dashboard-level RBAC: lets authors grant a dashboard to Superset roles
    # (Dashboard properties -> Access -> Roles). admin-center syncs those
    # grants (superset.dashboard_roles) and filters embedded dashboards by the
    # user's RBAC Mapping. Dashboards with no roles stay unrestricted.
    "DASHBOARD_RBAC": True,
}

# ==============================================================================
# Native-UI authentication — platform SSO via trusted edge-gateway headers.
# ------------------------------------------------------------------------------
# Authors/admins opening the Superset UI (/bi/) are authenticated by the
# edge gateway (validates platform JWT, injects X-Remote-User / X-Remote-Roles,
# strips client-forged headers). See superset_security_manager.py.
# The embedded guest-token viewer path is unaffected (no login session).
# ==============================================================================
AUTH_TYPE = AUTH_REMOTE_USER
AUTH_REMOTE_USER_ENV_VAR = "HTTP_X_REMOTE_USER"
AUTH_USER_REGISTRATION = True
AUTH_USER_REGISTRATION_ROLE = "Public"  # minimal until roles are mapped by the gateway
CUSTOM_SECURITY_MANAGER = PlatformRemoteUserSecurityManager

# ==============================================================================
# Guest Token (Embedded Dashboard)
# ------------------------------------------------------------------------------
# Defaults to SECRET_KEY (Superset's own default). Override with a dedicated
# secret only if you want to rotate it independently.
# ==============================================================================
GUEST_ROLE_NAME = os.getenv("SUPERSET_GUEST_ROLE_NAME", "Gamma")
GUEST_TOKEN_JWT_SECRET = os.getenv("SUPERSET_GUEST_TOKEN_JWT_SECRET") or SECRET_KEY
GUEST_TOKEN_JWT_ALGO = "HS256"
GUEST_TOKEN_HEADER_NAME = "X-GuestToken"
GUEST_TOKEN_JWT_EXP_SECONDS = 300

# Superset 6.0's common_bootstrap_payload (used by the embedded-dashboard view)
# unconditionally reads these keys; this minimal config must define them or the
# embedded render returns HTTP 500 (KeyError: 'RECAPTCHA_PUBLIC_KEY').
RECAPTCHA_PUBLIC_KEY = os.getenv("SUPERSET_RECAPTCHA_PUBLIC_KEY", "")
RECAPTCHA_PRIVATE_KEY = os.getenv("SUPERSET_RECAPTCHA_PRIVATE_KEY", "")

# Logout under gateway/REMOTE_USER SSO: Superset's own logout can't end the session
# (the gate re-authenticates from the still-valid platform JWT cookie). Redirect the
# Logout button to the platform endpoint that clears that cookie, then lands on login.
LOGOUT_REDIRECT_URL = os.getenv(
    "SUPERSET_LOGOUT_REDIRECT_URL",
    "http://localhost:3000/api/v1/admin/auth/logout-redirect",
)

# ==============================================================================
# CORS — restrict to the portal origin(s) that embed dashboards.
# Was origins=["*"] (any site could call Superset APIs with credentials).
# ==============================================================================
_cors_origins = [
    o.strip()
    for o in os.getenv("SUPERSET_CORS_ORIGINS", "http://localhost:3000").split(",")
    if o.strip()
]
ENABLE_CORS = True
CORS_OPTIONS = {
    "supports_credentials": True,
    "allow_headers": ["*"],
    "resources": ["/api/*", "/superset/csrf_token/", "/guest_token/"],
    "origins": _cors_origins,
}

# ==============================================================================
# Framing — allow only the portal origin(s) to iframe Superset.
# Was X-Frame-Options: ALLOWALL (any site could frame it -> clickjacking).
# CSP frame-ancestors is the modern allowlist (X-Frame-Options can't express one).
# ==============================================================================
TALISMAN_ENABLED = False
CONTENT_SECURITY_POLICY_WARNING = False
_frame_ancestors = " ".join(["'self'"] + _cors_origins)
HTTP_HEADERS = {
    "Content-Security-Policy": f"frame-ancestors {_frame_ancestors};",
}

# Exempt the guest_token API from WTF CSRF so the backend can call it
# with just the X-CSRFToken header + session cookie (no form-based token)
WTF_CSRF_EXEMPT_LIST = [
    "superset.security.api",
]

# ==============================================================================
# HSBC Brand Theme
# ------------------------------------------------------------------------------
# Primary: HSBC Red #DB0011  |  Dark: #B3000E
# Text:    #333333            |  Background: #FFFFFF / #F5F5F5
# ==============================================================================

# Chart color palette — HSBC red as primary, complementary business colors
EXTRA_CATEGORICAL_COLORS = [
    "#DB0011",  # HSBC Red
    "#333333",  # HSBC Dark Gray
    "#7A0010",  # Deep Red
    "#0066CC",  # Trust Blue
    "#E85D2C",  # Warm Orange
    "#2D8C4A",  # Forest Green
    "#8C5A9E",  # Rich Purple
    "#CC6600",  # Amber
    "#006B6B",  # Teal
    "#B3000E",  # Dark HSBC Red
    "#555555",  # Mid Gray
    "#E6007E",  # Magenta
]

EXTRA_SEQUENTIAL_COLORS = [
    "#FFF5F5", "#F5D5D9", "#EBAAB3", "#E07F8D",
    "#D65467", "#CC2A41", "#C2001B", "#B8000A",
    "#990000", "#7A0000",
]

THEME_OVERRIDES = {
    "borderRadius": 4,
    "colors": {
        "primary": {
            "base": "#DB0011",
            "dark1": "#B3000E",
            "dark2": "#8A000B",
            "light1": "#F5D5D9",
            "light2": "#FDE8EA",
        },
        "secondary": {
            "base": "#333333",
            "dark1": "#222222",
            "dark2": "#111111",
            "light1": "#E8E8E8",
            "light2": "#F5F5F5",
        },
        "grayscale": {
            "base": "#666666",
            "dark1": "#333333",
            "dark2": "#111111",
            "light1": "#CCCCCC",
            "light2": "#F5F5F5",
            "light3": "#FAFAFA",
            "light4": "#FFFFFF",
            "light5": "#FFFFFF",
        },
        "error": {
            "base": "#DB0011",
        },
        "warning": {
            "base": "#E85D2C",
        },
        "success": {
            "base": "#2D8C4A",
        },
        "info": {
            "base": "#0066CC",
        },
    },
    "typography": {
        "family": "'Helvetica Neue', Helvetica, Arial, sans-serif",
    },
}
