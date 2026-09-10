package io.github.rvbcrs.wallmode

/** Browser counterparts of the native Aurora Rail colors, shapes and vector icons. */
internal object LocalControlStyle {
    fun css(themeMode: ThemeMode): String {
        // Keep these palettes aligned with res/values[-night]/colors.xml.
        val light = """
            :root {
              color-scheme: light;
              --bg:#EAF2F4; --page-start:#F2F8F9; --page-center:#EAF4F6; --page-end:#E9F5F1;
              --orb-primary:rgba(48,199,216,.22); --orb-secondary:rgba(45,185,157,.15);
              --panel:rgba(255,255,255,.97); --rail:rgba(247,251,252,.98); --bar:#F7FBFC;
              --line:rgba(185,201,206,.75); --rail-line:rgba(180,199,204,.65);
              --text:#10242C; --muted:#526A73; --quiet:#526A73; --nav-text:#526A73;
              --accent:#18AFC1; --accent-text:#006E7B; --accent-hover:#30BECE; --accent-faint:rgba(24,175,193,.16);
              --amber:#B96B00;
              --on-accent:#031417; --field-bg:#FFFFFF; --switch-off:#C3D4D9; --switch-knob:#526A73;
              --success:#126E46; --notice-bg:#EAF7F2; --notice-line:rgba(85,184,145,.5);
              --danger:#C54242; --danger-bg:#FFF1F2; --danger-line:rgba(215,90,100,.5);
              --shadow:0 12px 32px rgba(17,46,54,.045);
            }
        """.trimIndent()
        val dark = """
            :root {
              color-scheme: dark;
              --bg:#050A0E; --page-start:#061116; --page-center:#03090D; --page-end:#071514;
              --orb-primary:rgba(40,199,216,.18); --orb-secondary:rgba(34,168,141,.13);
              --panel:rgba(17,26,34,.95); --rail:rgba(11,18,24,.95); --bar:#0B1218;
              --line:#253640; --rail-line:rgba(59,86,98,.4);
              --text:#F4F7FA; --muted:#9AAAB5; --quiet:#84969F; --nav-text:#84969F;
              --accent:#28C7D8; --accent-text:#28C7D8; --accent-hover:#54D6E3; --accent-faint:rgba(40,199,216,.2);
              --amber:#FFB547;
              --on-accent:#031417; --field-bg:#0C141B; --switch-off:#293E48; --switch-knob:#9AAAB5;
              --success:#55D58A; --notice-bg:#10211F; --notice-line:rgba(85,213,138,.4);
              --danger:#EB6C6C; --danger-bg:#30171A; --danger-line:rgba(255,101,112,.4);
              --shadow:0 12px 32px rgba(0,0,0,.12);
            }
        """.trimIndent()
        val palette = when (themeMode) {
            ThemeMode.LIGHT -> light
            ThemeMode.DARK -> dark
            ThemeMode.SYSTEM -> "$light\n@media (prefers-color-scheme: dark) { $dark }"
        }
        return """
            $palette
            * { box-sizing:border-box; }
            html { min-width:320px; min-height:100%; }
            body {
              margin:0; min-height:100vh; color:var(--text); background-color:var(--bg);
              background-image:radial-gradient(circle at 92% 0%,var(--orb-primary),transparent 38%),
                radial-gradient(circle at 8% 100%,var(--orb-secondary),transparent 38%),
                linear-gradient(135deg,var(--page-start),var(--page-center) 60%,var(--page-end));
              background-attachment:fixed; font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif;
            }
            a { color:var(--accent-text); }
            button,input,select,textarea { font:inherit; }
            button,a,input,select,textarea { -webkit-tap-highlight-color:transparent; }
            h1,h2,h3,p { margin:0; }
            h1 { font-size:1.65rem; font-weight:650; letter-spacing:-.035em; line-height:1.25; }
            h2 { font-size:1.16rem; font-weight:600; letter-spacing:-.02em; }
            h3 { font-size:1rem; font-weight:600; }
            p { overflow-wrap:break-word; }
            [hidden] { display:none !important; }
            .shell { display:grid; grid-template-columns:240px minmax(0,1fr); gap:20px; max-width:1440px; margin:auto; padding:24px; }
            .rail {
              position:sticky; top:24px; align-self:start; max-height:calc(100vh - 48px); overflow:auto;
              padding:18px 12px 12px; border:1px solid var(--rail-line); border-radius:24px;
              background:var(--rail); box-shadow:var(--shadow);
            }
            .brand { display:flex; align-items:center; gap:12px; min-width:0; padding:0 8px; color:var(--text); text-decoration:none; }
            .brand > .brand-icon {
              flex:0 0 40px; width:40px; height:40px; padding:5px; border-radius:10px;
              background:#131B20;
            }
            .brand-icon svg { display:block; width:30px; height:30px; }
            .brand strong { display:block; font-size:1.18rem; font-weight:650; letter-spacing:0; }
            .brand small { display:block; color:var(--muted); font-size:.75rem; }
            .nav-caption,.eyebrow { font-size:.69rem; font-weight:650; text-transform:uppercase; letter-spacing:.12em; }
            .nav-caption { margin:25px 12px 9px; color:var(--quiet); }
            .eyebrow { color:var(--accent-text); margin-bottom:5px; }
            .nav { display:grid; gap:5px; }
            .nav-link {
              display:flex; align-items:center; gap:12px; min-height:52px; min-width:0;
              padding:11px 13px; border:1px solid transparent; border-radius:16px;
              color:var(--nav-text); text-decoration:none; font-size:.92rem; font-weight:550;
              transition:background-color 150ms ease,color 150ms ease;
            }
            .nav-icon { display:flex; flex:0 0 24px; }
            .nav-icon svg { width:24px; height:24px; }
            .nav-link:hover { color:var(--text); background:var(--accent-faint); }
            .nav-link[aria-current="page"],.nav-link.active {
              color:var(--accent-text); background:var(--accent-faint); border-color:var(--rail-line);
              box-shadow:inset 3px 0 var(--accent);
            }
            .rail-footer { margin-top:24px; padding:14px 12px; border:1px solid var(--notice-line); border-radius:16px; background:var(--notice-bg); }
            .connection { display:flex; align-items:center; gap:8px; color:var(--success); font-size:.8rem; font-weight:600; }
            .status-dot { flex:0 0 7px; width:7px; height:7px; border-radius:50%; background:var(--success); }
            .access-url { display:block; margin-top:5px; color:var(--muted); font-size:.73rem; word-break:break-word; overflow-wrap:anywhere; }
            .workspace,.page-content { min-width:0; }
            .page-header {
              display:flex; align-items:center; gap:16px; padding:22px 24px; margin-bottom:16px;
              border:1px solid var(--line); border-radius:24px; background:var(--rail);
            }
            .page-header > div { min-width:0; }
            .page-header p:not(.eyebrow) { margin-top:6px; color:var(--muted); font-size:.86rem; }
            .page-symbol { display:flex; align-items:center; justify-content:center; flex:0 0 48px; height:48px; border-radius:16px; background:var(--accent-faint); color:var(--accent); }
            .page-symbol svg { width:28px; height:28px; }
            .card,.settings-group { min-width:0; margin:0 0 16px; padding:22px; border:1px solid var(--line); border-radius:24px; background:var(--panel); box-shadow:var(--shadow); }
            .card h2 { margin-bottom:12px; }
            .card > .muted { margin-bottom:16px; }
            .card.settings-card { padding:0; border:0; background:transparent; box-shadow:none; }
            .settings-card > h2 { margin:0 0 8px; }
            .settings-card > .muted,.section-help { color:var(--muted); font-size:.86rem; }
            .settings-card > .muted { padding:0 4px; }
            .settings-form { display:grid; grid-template-columns:minmax(0,1fr); gap:16px; }
            .settings-group { margin:0; padding-top:16px; }
            .settings-group legend,fieldset.card legend { padding:0 8px; color:var(--text); font-size:1rem; font-weight:600; letter-spacing:-.015em; }
            .section-help { margin:0 0 16px; }
            .settings-grid { display:grid; grid-template-columns:minmax(0,1fr); gap:18px 20px; }
            .setting-field { display:grid; min-width:0; gap:7px; align-content:start; }
            .setting-field > label,.label { color:var(--muted); font-size:.83rem; font-weight:550; }
            .setting-field-wide,.settings-grid > .check { grid-column:1 / -1; }
            .field { display:block; width:100%; min-width:0; min-height:48px; padding:11px 13px; border:1px solid var(--line); border-radius:14px; background:var(--field-bg); color:var(--text); }
            .field::placeholder { color:var(--quiet); opacity:1; }
            select.field { padding-right:12px; }
            .check { display:flex; align-items:center; justify-content:space-between; gap:14px; min-width:0; min-height:52px; color:var(--text); cursor:pointer; }
            .check input { flex-shrink:0; width:21px; height:21px; margin:0; accent-color:var(--accent); }
            .check input[type="radio"] { margin-right:4px; }
            @supports (-webkit-appearance:none) or (appearance:none) {
              .check input[type="checkbox"] {
                -webkit-appearance:none; appearance:none; position:relative; order:1; flex:0 0 44px;
                width:44px; height:26px; margin-left:auto; border:1px solid var(--line); border-radius:30px;
                background:var(--switch-off); cursor:pointer; transition:background-color 150ms ease;
              }
              .check input[type="checkbox"]::before {
                content:""; display:block; position:absolute; left:4px; top:4px; width:16px; height:16px;
                border-radius:50%; background:var(--switch-knob); transition:transform 150ms ease;
              }
              .check input[type="checkbox"]:checked { background:var(--accent); border-color:var(--accent); }
              .check input[type="checkbox"]:checked::before { transform:translateX(18px); background:var(--on-accent); }
            }
            .save-bar {
              position:sticky; bottom:12px; z-index:2; display:flex; align-items:center; justify-content:space-between; gap:16px;
              padding:12px 14px; border:1px solid var(--rail-line); border-radius:18px;
              background:var(--bar); box-shadow:0 8px 24px rgba(0,0,0,.12);
            }
            .save-result { min-width:0; margin:0; font-size:.86rem; color:var(--muted); overflow-wrap:anywhere; }
            .save-result:empty { display:none; }
            .save-result[data-error="true"] { color:var(--danger); }
            .save-bar > .btn { flex-shrink:0; margin-left:auto; }
            .btn {
              display:inline-flex; align-items:center; justify-content:center; gap:8px; min-height:48px; max-width:100%;
              padding:11px 16px; border:1px solid var(--rail-line); border-radius:14px; background:var(--field-bg);
              color:var(--text); font:inherit; font-size:.9rem; font-weight:600; text-align:center; text-decoration:none;
              cursor:pointer; transition:background-color 150ms ease,border-color 150ms ease;
            }
            .btn svg { flex-shrink:0; width:20px; height:20px; }
            .btn:hover { border-color:var(--accent); background:var(--accent-faint); }
            .btn.primary { border-color:var(--accent); background:var(--accent); color:var(--on-accent); }
            .btn.primary:hover { background:var(--accent-hover); }
            .btn:disabled { opacity:.55; cursor:wait; }
            .support-card { border-color:var(--amber); background:linear-gradient(135deg,rgba(255,181,71,.09),transparent 65%),var(--panel); }
            .support-card h2 { font-weight:700; }
            .support-link { justify-content:flex-start; gap:12px; padding:14px; border-color:var(--amber); text-align:left; }
            .support-link:hover { border-color:var(--amber); background:rgba(255,181,71,.12); }
            .support-link.coffee { background:var(--amber); color:#000; }
            .support-link.coffee .support-label small { color:inherit; }
            .support-link.coffee .support-icon { color:inherit; background:rgba(0,0,0,.08); }
            .support-icon { display:flex; flex:0 0 40px; align-items:center; justify-content:center; height:40px; border-radius:12px; color:var(--amber); background:rgba(255,181,71,.12); }
            .support-icon svg { width:24px; height:24px; }
            .support-label { flex:1; min-width:0; overflow-wrap:anywhere; }
            .support-label strong,.support-label small { display:block; }
            .support-label small { margin-top:3px; font-size:.8rem; font-weight:400; color:var(--muted); }
            .support-note { margin-top:14px; font-size:.8rem; }
            .visually-hidden { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
            .notice,.error { padding:14px 16px; margin-bottom:16px; border:1px solid var(--notice-line); border-radius:16px; background:var(--notice-bg); color:var(--success); overflow-wrap:anywhere; }
            .error { border-color:var(--danger-line); background:var(--danger-bg); color:var(--danger); }
            .muted { color:var(--muted); }
            .stack { display:grid; gap:12px; min-width:0; }
            .row { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
            .tile-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(210px,1fr)); gap:12px; }
            .tile-grid .stack { align-content:start; }
            .status { display:grid; grid-template-columns:180px minmax(0,1fr); gap:0 18px; margin:0 0 18px; }
            .status dt,.status dd { padding:12px 0; border-bottom:1px solid var(--line); font-size:.9rem; }
            .status dt { color:var(--muted); }
            .status dd { margin:0; overflow-wrap:anywhere; word-break:break-word; }
            .screen-preview { display:block; width:100%; height:auto; margin-top:16px; border:1px solid var(--line); border-radius:16px; background:#000; }
            a:focus,button:focus,input:focus,select:focus,textarea:focus { outline:2px solid var(--accent-text); outline-offset:3px; }
            :focus:not(:focus-visible) { outline:none; }
            .skip-link { position:fixed; z-index:10; top:12px; left:12px; padding:12px 18px; border-radius:12px; background:var(--accent); color:var(--on-accent); transform:translateY(-160%); }
            .skip-link:focus { transform:translateY(0); }
            .login-page { display:flex; align-items:center; justify-content:center; min-height:100vh; padding:24px; }
            .login-card { width:100%; max-width:440px; padding:32px; border:1px solid var(--rail-line); border-radius:24px; background:var(--rail); box-shadow:var(--shadow); }
            .login-card .brand { padding:0; margin-bottom:32px; }
            .login-card h1 { margin-bottom:12px; }
            .login-card > p:not(.eyebrow) { color:var(--muted); font-size:.9rem; }
            .login-card .stack { margin-top:24px; }
            .login-card .error { margin-top:20px; }
            .login-footer { margin-top:24px; padding-top:18px; border-top:1px solid var(--line); color:var(--quiet); font-size:.78rem; }
            @media (min-width:1100px) { .settings-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } }
            @media (max-width:1000px) and (min-width:761px) {
              .shell { grid-template-columns:204px minmax(0,1fr); gap:14px; padding:18px; }
              .rail { top:18px; max-height:calc(100vh - 36px); }
              .status { grid-template-columns:140px minmax(0,1fr); }
              .page-header { padding:20px; }
            }
            @media (max-width:760px) {
              .shell { grid-template-columns:minmax(0,1fr); gap:12px; padding:12px; }
              .rail { position:static; max-height:none; overflow:visible; padding:12px 8px 8px; border-radius:20px; }
              .rail .brand { margin:0 4px 12px; }
              .nav-caption,.rail-footer { display:none; }
              .nav { grid-template-columns:repeat(4,minmax(0,1fr)); gap:4px; }
              .nav-link { flex-direction:column; justify-content:center; gap:5px; padding:9px 3px; min-height:70px; border-radius:13px; font-size:.69rem; text-align:center; }
              .nav-link span:not(.nav-icon) { max-width:100%; overflow-wrap:break-word; }
              .nav-icon { flex-basis:auto; }
              .nav-icon svg { width:26px; height:26px; }
              .nav-link[aria-current="page"],.nav-link.active { box-shadow:inset 0 -3px var(--accent); }
              .page-header { padding:18px; margin-bottom:12px; gap:12px; border-radius:20px; }
              h1 { font-size:1.4rem; }
              .page-symbol { flex-basis:40px; height:40px; border-radius:13px; }
              .page-symbol svg { width:24px; height:24px; }
              .card,.settings-group { padding:18px 16px; margin-bottom:12px; border-radius:20px; }
              .settings-group { margin-bottom:0; }
              .settings-grid { gap:16px; }
              .save-bar { flex-direction:column; align-items:stretch; gap:10px; bottom:8px; padding:12px; }
              .save-bar > .btn { width:100%; margin-left:0; }
              .status { grid-template-columns:minmax(0,1fr); }
              .status dt { padding:12px 0 0; border-bottom:0; font-size:.8rem; }
              .status dd { padding:3px 0 12px; }
              .tile-grid { grid-template-columns:minmax(0,1fr); }
              .login-page { padding:16px; }
              .login-card { padding:24px; }
            }
            @media (prefers-reduced-motion:reduce) { *,*::before,*::after { transition:none !important; scroll-behavior:auto !important; } }
        """.trimIndent()
    }

    fun icon(name: String): String {
        if (name == "brand") return """
            <svg aria-hidden="true" focusable="false" viewBox="0 0 24 24" width="24" height="24">
              <path fill="none" stroke="#28C7D8" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" d="M10.2,6.4L5.2,5.05C3.55,4.6 2,5.65 2,7.35V17C2,18.1 2.9,19 4,19H20C21.1,19 22,18.1 22,17V7.35C22,5.65 20.45,4.6 18.8,5.05L13.8,6.4"/>
              <path fill="#28C7D8" d="M5.4,8.5C5.4,8.05 5.8,7.75 6.2,7.9L10.5,9.35C10.8,9.45 11,9.7 11,10.05V15.95C11,16.2 10.9,16.4 10.7,16.55L9.9,17.1C9.7,17.25 9.45,17.3 9.2,17.2L5.85,16.05C5.55,15.95 5.4,15.7 5.4,15.4Z"/>
              <path fill="#AEF6D8" d="M18.6,8.5C18.6,8.05 18.2,7.75 17.8,7.9L13.5,9.35C13.2,9.45 13,9.7 13,10.05V15.95C13,16.2 13.1,16.4 13.3,16.55L14.1,17.1C14.3,17.25 14.55,17.3 14.8,17.2L18.15,16.05C18.45,15.95 18.6,15.7 18.6,15.4Z"/>
            </svg>
        """.trimIndent()
        // Native paths: ic_nav_{dashboard,display,browser,device,system}, ic_action_{refresh,check}.
        val path = when (name) {
            "dashboard" -> "M4.5,3.5H9.5A1.5,1.5 0,0 1,11,5V9.5A1.5,1.5 0,0 1,9.5,11H4.5A1.5,1.5 0,0 1,3,9.5V5A1.5,1.5 0,0 1,4.5,3.5ZM14.5,3.5H19.5A1.5,1.5 0,0 1,21,5V6.5A1.5,1.5 0,0 1,19.5,8H14.5A1.5,1.5 0,0 1,13,6.5V5A1.5,1.5 0,0 1,14.5,3.5ZM4.5,13H9.5A1.5,1.5 0,0 1,11,14.5V19A1.5,1.5 0,0 1,9.5,20.5H4.5A1.5,1.5 0,0 1,3,19V14.5A1.5,1.5 0,0 1,4.5,13ZM14.5,10H19.5A1.5,1.5 0,0 1,21,11.5V19A1.5,1.5 0,0 1,19.5,20.5H14.5A1.5,1.5 0,0 1,13,19V11.5A1.5,1.5 0,0 1,14.5,10Z"
            "display" -> "M4,4H20A2,2 0,0 1,22,6V16A2,2 0,0 1,20,18H4A2,2 0,0 1,2,16V6A2,2 0,0 1,4,4ZM8,21H16M12,18V21"
            "browser" -> "M12,3A9,9 0,1 0,12,21A9,9 0,1 0,12,3ZM3.5,9H20.5M3.5,15H20.5M12,3C14.5,5.5 15.5,8.5 15.5,12C15.5,15.5 14.5,18.5 12,21C9.5,18.5 8.5,15.5 8.5,12C8.5,8.5 9.5,5.5 12,3Z"
            "device" -> "M7,2.5H17A2,2 0,0 1,19,4.5V19.5A2,2 0,0 1,17,21.5H7A2,2 0,0 1,5,19.5V4.5A2,2 0,0 1,7,2.5ZM5,17.5H19M11,5H13M11.5,19.5H12.5"
            "system" -> "M3,6H7M11,6H21M3,12H13M17,12H21M3,18H9M13,18H21M9,4A2,2 0,1 0,9,8A2,2 0,1 0,9,4ZM15,10A2,2 0,1 0,15,14A2,2 0,1 0,15,10ZM11,16A2,2 0,1 0,11,20A2,2 0,1 0,11,16Z"
            "actions" -> "M20,5V10H15M4,19V14H9M5.8,9A7,7 0,0 1,18.6,7L20,10M4,14L5.4,17A7,7 0,0 0,18.2,15"
            "save" -> "M5,12.5L9.5,17L19,7.5"
            "coffee" -> "M4,8H17V14A5,5 0,0 1,12,19H9A5,5 0,0 1,4,14ZM17,8H19A3,3 0,0 1,19,14H17M3,22H19M7,3V5M11,2V5M15,3V5"
            "heart" -> "M20.8,4.6A5.5,5.5 0,0 0,13,4.6L12,5.6L11,4.6A5.5,5.5 0,0 0,3.2,12.4L12,21.2L20.8,12.4A5.5,5.5 0,0 0,20.8,4.6Z"
            "external" -> "M14,3H21V10M21,3L10,14M10,3H5A2,2 0,0 0,3,5V19A2,2 0,0 0,5,21H19A2,2 0,0 0,21,19V14"
            // Matching rounded outlines for the browser-only overview and security sections.
            "security" -> "M12,3L20,6V11C20,16 17,19 12,21C7,19 4,16 4,11V6L12,3ZM8.5,12L11,14.5L15.5,10"
            else -> "M3,12H7L10,5L14,19L17,12H21"
        }
        return """<svg aria-hidden="true" focusable="false" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="$path"/></svg>"""
    }
}
