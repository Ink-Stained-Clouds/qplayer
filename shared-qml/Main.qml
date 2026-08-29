import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "components"
import "dialogs"
import "pages"
import "settings"

// Phone shell: TopAppBar + paged content + mini player + bottom navigation,
// with a playlist-detail overlay, QR login dialog, a Snackbar for transient
// messages and the debug log on top.
Rectangle {
    id: app
    color: Theme.color.surface

    property int page: 0
    property int nextPage: 0
    property bool detailOpen: false
    property bool loginOpen: false
    property bool settingsOpen: false
    property bool accountOpen: false
    property bool cacheListOpen: false
    property bool showLog: false
    // Menu.open() registers the one top-level popup currently attached to this
    // scene. Song rows each own a lazy menu instance, so without a scene-wide
    // owner repeated right-clicks can leave every row's overlay open at once.
    property var activeMenu: null

    property var titles: ["推荐", "搜索", "我的", "本地"]
    property bool showLocalTab: settings.value("showLocalTab")
    onShowLocalTabChanged: {
        if (!showLocalTab && app.page === 3) app.switchTo(0)
    }

    // Responsive breakpoints (MD3): compact < 600, medium 600–839, expanded ≥ 840.
    // The wide layout (a NavigationRail on the left instead of the bottom bar) is
    // driven purely by the available width, so a tablet, a desktop window, or even
    // a phone in landscape adopts it automatically once the width threshold is met.
    property bool wide: app.width >= 600
    property bool expanded: app.width >= 840

    // Shared nav model for both the bottom bar and the rail.
    property var navItems: showLocalTab
        ? [
            { icon: "recommend",     text: "推荐" },
            { icon: "search",        text: "搜索" },
            { icon: "library_music", text: "我的" },
            { icon: "folder",        text: "本地" }
          ]
        : [
            { icon: "recommend",     text: "推荐" },
            { icon: "search",        text: "搜索" },
            { icon: "library_music", text: "我的" }
          ]

    // Rebuild the debug log string only while it's actually shown (its set() forces a
    // full relayout, which periodically stuttered the scene when always rebuilt).
    onShowLogChanged: player.setLogVisible(app.showLog)

    // isDarkTheme follows the settings policy. seedColor (Monet) is driven from
    // PlayerController in Java -- a QML Binding on StyleManager.seedColor would not
    // re-fire when the cover seed changed.
    Binding {
        target: StyleManager; property: "isDarkTheme"
        value: settings.resolvedDark
    }

    // System back press (hardware + gesture): the host bumps player.backTick; pop the
    // topmost open overlay/page, and only ask the host to exit when nothing is open.
    property int backTick: player.backTick
    onBackTickChanged: app.handleBack()

    function handleBack() {
        // An unreadable credential envelope requires an explicit decision. Letting
        // outside click / Android back dismiss it would leave the app in an unclear
        // half-logged-in state with no path to retry or start over.
        if ((credentialNoticeDialog.opened && player.credentialNoticeType === 3)
                || credentialFallbackConfirmDialog.opened
                || credentialReloginUnavailableDialog.opened) return;
        // Order = top-most layer first. The lyric page (host-drawn) and the queue
        // sit above the QML overlays, so they must close before settings/login/log.
        if (player.lyricsOpen)      { player.setLyricsOpen(false); return; }
        if (player.queueOpen)       { player.setQueueOpen(false); return; }
        if (app.showLog)            { app.showLog = false; return; }
        if (app.loginOpen)          { app.loginOpen = false; return; }
        if (app.accountOpen)        { app.accountOpen = false; return; }
        if (app.cacheListOpen)      { app.cacheListOpen = false; return; }
        if (app.settingsOpen)       { app.settingsOpen = false; return; }
        // Artist/album drill-ins sit above the playlist detail (an album opened
        // from an artist page opened from a playlist), so they close first.
        if (player.albumPageOpen)   { player.setAlbumPageOpen(false); return; }
        if (player.artistPageOpen)  { player.setArtistPageOpen(false); return; }
        if (app.detailOpen)         { app.detailOpen = false; return; }
        if (app.page !== 0)         { app.switchTo(0); return; }
        player.requestExit();
    }

    // Detail/queue/settings/account are independent booleans, each just driving
    // its own overlay's opacity/y — nothing stopped two from being true at once
    // (e.g. opening account while settings was still open), so the later-declared
    // one visually covered the other. Route every "open X" site through this so
    // opening one always closes the rest first.
    function openOverlay(which) {
        app.detailOpen = which === "detail"
        app.settingsOpen = which === "settings"
        app.accountOpen = which === "account"
        app.cacheListOpen = which === "cachedSongs"
        player.setQueueOpen(which === "queue")
        // Artist/album pages are opened directly by player.openArtist/openAlbum
        // (not routed through here), but any OTHER destination replaces them.
        player.setArtistPageOpen(false)
        player.setAlbumPageOpen(false)
    }

    // MD3 fade-through page switch: fade the content out, swap, fade it back in.
    function switchTo(idx) {
        app.detailOpen = false;          // dismiss any open playlist detail
        app.settingsOpen = false;        // and the settings overlay
        app.accountOpen = false;         // and the account overlay
        app.cacheListOpen = false;       // and the cached-songs overlay
        player.setQueueOpen(false);      // and the queue overlay
        player.setArtistPageOpen(false); // and any open artist page
        player.setAlbumPageOpen(false);  // and any open album page
        if (idx === app.page) return;
        app.nextPage = idx;
        if (idx === 2) player.loadMyPlaylists();
        pageAnim.restart();
    }

    // Driven by pageAnim; pageBody binds y + opacity to these.
    property real pageOpacity: 1
    property real pageShift: 0

    SequentialAnimation {
        id: pageAnim
        NumberAnimation {
            target: app; property: "pageOpacity"; to: 0
            duration: 90; easing.type: Easing.OutCubic
        }
        ScriptAction { onTrigger: { app.page = app.nextPage; app.pageShift = 28 } }
        ParallelAnimation {
            NumberAnimation {
                target: app; property: "pageOpacity"; from: 0; to: 1
                duration: 220; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: app; property: "pageShift"; from: 28; to: 0
                duration: 220; easing.type: Easing.OutCubic
            }
        }
    }

    // Surface player toasts in both render passes. The host composites lyricChrome
    // over the normal QML scene while the lyric page is open, so a root-only toast
    // exists but is hidden behind that pass.
    function showToast(message) {
        snack.show(message)
        lyricOverlay.showToast(message)
    }
    property string toastWatch: player.toast
    onToastWatchChanged: if (player.toast.length > 0) app.showToast(player.toast)

    // Chrome is absolute/anchor-positioned, NOT a ColumnLayout. The play clock
    // sets player.positionMs ~5x/s; each set bumps the engine change version and
    // forces a whole-tree settleLayout that frame (and on coinciding scroll
    // frames). Layout containers in the always-visible chrome re-ran their
    // measure/fill passes every one of those ticks; anchors keep it cheap.
    // Wide-screen navigation rail (left), shown in place of the bottom bar once the
    // window is wide enough; collapses to width 0 (and hides) on compact widths so
    // the content reclaims the full width. Expands to a labelled rail at ≥ 840.
    NavigationRail {
        id: rail
        anchors.left: parent.left
        anchors.leftMargin: settings.leftInset
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        visible: app.wide
        extended: app.expanded
        width: app.wide ? implicitWidth : 0
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        currentIndex: app.page
        model: app.navItems
        sectionLabel: "导航"
        onItemClicked: app.switchTo(index)

        // Rail header: the app mark in the top-left corner, which only the wide
        // layout has room for (the compact layout's top-left is the TopAppBar's
        // title). The logo slides from centred (collapsed rail) to left-aligned
        // beside the name (extended rail) on the same 200ms curve the rail's own
        // width animates with; the name itself just fades, so the two states
        // don't fight over the 80px collapsed width.
        //
        // The desktop custom title bar (TitleBar.qml, below) already draws this
        // same icon+"QPlayer" mark once topInset reserves space for it -- showRailBrand
        // hides the rail's own copy in that case so the two don't stack. Still need
        // implicitHeight: 64 + settings.topInset unconditionally so the nav items
        // themselves don't creep up under the title bar.
        property bool showRailBrand: !hostWindow.available

        header: Item {
            // 桌面端：标题栏已遮挡 topInset 区域，header 只需 topInset 即可
            // （0~topInset 被标题栏遮挡不可见，导航项从 topInset+12 开始）
            // 移动端：需要额外 64px 给 Logo
            implicitHeight: rail.showRailBrand ? (64 + settings.topInset) : settings.topInset

            Image {
                id: railLogo
                width: 32
                height: 32
                // topInset is the reserved system/custom-title-bar strip. Centre
                // the brand in the 64px rail header below it so native desktop
                // decorations cannot cover its top edge when topInset is 0.
                y: settings.topInset + (parent.height - settings.topInset - height) / 2
                x: app.expanded ? 24 : (parent.width - width) / 2
                Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                visible: rail.showRailBrand
                source: "app-icon.png"
                // Decode straight to ~2x the drawn size. Without this the 256px
                // source is resampled to 32 at draw time with plain bilinear
                // (SamplingMode.LINEAR), which at an 8:1 ratio aliases the disc's
                // grooves badly; sourceSize routes it through the loader's
                // mipmapped downscale instead. The artwork already carries its own
                // rounded corners, so no radius here — clipping them a second time
                // just re-aliases the edge.
                sourceSize.width: 64
                sourceSize.height: 64
            }
            Text {
                anchors.left: railLogo.right
                anchors.leftMargin: 12
                anchors.verticalCenter: railLogo.verticalCenter
                text: "QPlayer"
                opacity: (app.expanded && rail.showRailBrand) ? 1 : 0
                visible: opacity > 0.01
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                color: Theme.color.onSurfaceColor
                font.family: Theme.typography.titleMedium.family
                font.pixelSize: Theme.typography.titleMedium.size
            }
        }

        // Brand mark below the header strip: the app icon + "QPlayer" wordmark.
        // On Windows the header strip itself sits behind the custom title bar
        // (which already draws the same mark), so showRailBrand is false there
        // and this copy shows instead — the rail still reads as the app. When
        // showRailBrand is true (mobile/edge-to-edge), the header's own logo
        // already covers it, so this hides (implicitHeight collapses to 0) to
        // avoid a second logo.
        headerActions: Item {
            implicitHeight: visible ? 56 : 0
            visible: !rail.showRailBrand

                Image {
                    id: actionsLogo
                    width: 32
                    height: 32
                    anchors.verticalCenter: parent.verticalCenter
                    // 与 rail header 的 logo 一致：扩展时左对齐，收起时居中，
                    // 而不是固定在居中偏左的位置。
                    x: app.expanded ? 24 : (parent.width - width) / 2
                    Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    source: "app-icon.png"
                    sourceSize.width: 64
                    sourceSize.height: 64
                }
                Text {
                    anchors.left: actionsLogo.right
                    anchors.leftMargin: 8
                    anchors.verticalCenter: actionsLogo.verticalCenter
                    text: "QPlayer"
                    color: Theme.color.onSurfaceColor
                    font.family: Theme.typography.titleLarge.family
                    font.pixelSize: Theme.typography.titleLarge.size
                }
        }

        // Secondary destinations live at the bottom of the wide rail instead of
        // competing with page-level actions in the top bar. Labels fade with the
        // extended rail; the compact rail keeps the same icon targets.
        footer: Item {
            // Logged-in users get the listen-together action next to the account
            // entry on both the compact rail (tablets) and the extended rail
            // (desktop). Collapse the extra row entirely while signed out.
            implicitHeight: player.loggedIn ? 212 : 164

            Rectangle {
                x: 12
                y: 0
                width: parent.width - 24
                height: 1
                color: Theme.color.outlineVariant
            }

            Repeater {
                model: player.loggedIn
                    ? [
                        { action: "download", icon: "download", text: "已下载" },
                        { action: "together", icon: "group", text: "一起听" },
                        { action: "account", icon: "account_circle", text: "账户" },
                        { action: "settings", icon: "settings", text: "设置" }
                      ]
                    : [
                        { action: "download", icon: "download", text: "已下载" },
                        { action: "account", icon: "login", text: "登录" },
                        { action: "settings", icon: "settings", text: "设置" }
                      ]

                Item {
                    id: footerAction
                    x: 0
                    y: 10 + index * 48
                    width: parent.width
                    height: 48

                    Rectangle {
                        id: footerState
                        property color hoverColor: Theme.color.surfaceContainerHighest
                        x: app.expanded ? 12 : (parent.width - 48) / 2
                        y: 2
                        width: app.expanded ? parent.width - 24 : 48
                        height: 44
                        radius: 22
                        color: footerRipple.containsMouse
                               ? hoverColor
                               : Qt.rgba(hoverColor.r, hoverColor.g, hoverColor.b, 0)
                        Behavior on color { ColorAnimation { duration: 140 } }
                    }

                    Text {
                        x: app.expanded ? 28 : (parent.width - width) / 2
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.icon
                        font.family: Theme.iconFont.name
                        font.pixelSize: 22
                        color: modelData.action === "together" && player.listenTogetherInRoom
                               ? Theme.color.primary : Theme.color.onSurfaceVariantColor
                        Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                    }

                    Text {
                        x: 64
                        anchors.verticalCenter: parent.verticalCenter
                        width: parent.width - 76
                        text: modelData.text
                        color: Theme.color.onSurfaceColor
                        font.family: Theme.typography.labelLarge.family
                        font.pixelSize: Theme.typography.labelLarge.size
                        elide: Text.ElideRight
                        opacity: app.expanded ? 1 : 0
                        visible: opacity > 0.01
                        Behavior on opacity { NumberAnimation { duration: 160 } }
                    }

                    Ripple {
                        id: footerRipple
                        x: footerState.x
                        y: footerState.y
                        width: footerState.width
                        height: footerState.height
                        clipRadius: footerState.radius
                        rippleColor: Theme.color.onSurfaceColor
                        onClicked: {
                            if (modelData.action === "download") {
                                player.refreshCachedSongs()
                                app.openOverlay("cachedSongs")
                            } else if (modelData.action === "together") {
                                togetherDialog.open()
                            } else if (modelData.action === "account") {
                                if (player.loggedIn) app.openOverlay("account")
                                else app.loginOpen = true
                            } else {
                                app.openOverlay("settings")
                            }
                        }
                    }
                }
            }
        }
    }

    TopAppBar {
        id: topBar
        anchors.top: parent.top
        anchors.topMargin: settings.topInset   // clear the status bar (edge-to-edge)
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        height: 64
        title: app.titles[app.page]
        showNavigationIcon: false

        IconButton {
            type: "standard"
            icon: "queue_music"
            onClicked: app.openOverlay("queue")
        }
        IconButton {
            visible: !app.wide
            type: "standard"
            icon: "download"
            onClicked: {
                player.refreshCachedSongs()
                app.openOverlay("cachedSongs")
            }
        }
        IconButton {
            visible: !app.wide && player.loggedIn
            type: "standard"
            icon: "group"
            contentColor: player.listenTogetherInRoom
                          ? Theme.color.primary : Theme.color.onSurfaceVariantColor
            onClicked: togetherDialog.open()
        }
        IconButton {
            visible: !app.wide
            type: "standard"
            icon: player.loggedIn ? "account_circle" : "login"
            onClicked: if (player.loggedIn) app.openOverlay("account"); else app.loginOpen = true
        }
        IconButton {
            visible: !app.wide
            type: "standard"
            icon: "settings"
            onClicked: app.openOverlay("settings")
        }
        IconButton {
            type: "standard"
            icon: "bug_report"
            onClicked: app.showLog = !app.showLog
        }
    }

    // content region. pageBody is positioned by y (not anchors) so the
    // switch can rise it up; pageWrap clips the overshoot.
    Item {
        id: pageWrap
        anchors.top: topBar.bottom
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        anchors.bottom: mini.top
        clip: true

        Item {
            id: pageBody
            width: parent.width
            height: parent.height
            y: app.pageShift
            opacity: app.pageOpacity

            // Pages stacked + toggled by visibility (was a StackLayout). The
            // engine doesn't recurse into an invisible child's subtree during
            // measure, so only the current page is laid out each frame.
            HomePage {
                id: home
                anchors.fill: parent
                visible: app.page === 0
                onOpenPlaylist: { player.openPlaylist(home.pendingPlaylist.id); app.openOverlay("detail") }
            }
            SearchPage {
                anchors.fill: parent
                visible: app.page === 1
            }
            LibraryPage {
                id: library
                anchors.fill: parent
                visible: app.page === 2
                onOpenPlaylist: { player.openPlaylist(library.pendingPlaylist.id); app.openOverlay("detail") }
                onRequestLogin: app.loginOpen = true
            }
            LocalPage {
                anchors.fill: parent
                visible: app.page === 3
            }

            // Overlays animate in: detail drills in from the right, queue and
            // settings rise from below. Kept laid out only while opacity > 0 so a
            // closed overlay costs nothing per frame.
            PlaylistDetailPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.detailOpen ? 1 : 0
                x: app.detailOpen ? 0 : 36
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on x { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.detailOpen = false
            }

            ArtistDetailPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: player.artistPageOpen ? 1 : 0
                x: player.artistPageOpen ? 0 : 36
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on x { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: player.setArtistPageOpen(false)
            }

            AlbumDetailPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: player.albumPageOpen ? 1 : 0
                x: player.albumPageOpen ? 0 : 36
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on x { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: player.setAlbumPageOpen(false)
            }

            QueuePage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: player.queueOpen ? 1 : 0
                y: player.queueOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: player.setQueueOpen(false)
            }

            SettingsPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.settingsOpen ? 1 : 0
                y: app.settingsOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.settingsOpen = false
            }

            AccountPage {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.accountOpen ? 1 : 0
                y: app.accountOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.accountOpen = false
            }

            CachedSongsDialog {
                width: parent.width
                height: parent.height
                visible: opacity > 0.001
                opacity: app.cacheListOpen ? 1 : 0
                y: app.cacheListOpen ? 0 : 32
                Behavior on opacity { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
                Behavior on y { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
                onBack: app.cacheListOpen = false
            }
        }
    }

    MiniPlayer {
        id: mini
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        anchors.bottom: bottomNav.top
        height: 84
        onLyricsRequested: player.setLyricsOpen(true)
    }

    // Bottom navigation (compact). On wide layouts the rail replaces it, so collapse
    // it to height 0 + hidden; the mini player (anchored to bottomNav.top) then sits
    // flush at the bottom without a conditional anchor.
    BottomNav {
        id: bottomNav
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        anchors.bottom: parent.bottom
        visible: !app.wide
        // Nav content sits in the top 76; the extra height is background that fills
        // behind the gesture/navigation bar (edge-to-edge).
        height: app.wide ? 0 : (76 + settings.bottomInset)
        currentIndex: app.page
        items: app.navItems
        onNavigate: app.switchTo(bottomNav.pendingIndex)
    }

    // Lyric page chrome (title / wavy progress / transport), over the host-drawn
    // fluid backdrop + lyrics. Slides up from the bottom in lockstep with the host
    // layer -- same smoothstep(player.lyricSlide) offset the host applies.
    LyricOverlay {
        id: lyricOverlay
        objectName: "lyricChrome"   // host renders this subtree in its own pass, over the fluid
        x: settings.leftInset
        width: parent.width - settings.leftInset - settings.rightInset
        height: parent.height
        visible: player.lyricSlide > 0.001
        // Desktop hides its custom title bar while the lyric page is open (see the
        // TitleBar below), so the three title buttons sit flush at the very top.
        topPad: hostWindow.available ? 6 : settings.topInset + 6
        y: {
            var s = player.lyricSlide;
            return (1 - s * s * (3 - 2 * s)) * height;
        }
    }

    LoginDialog {
        active: app.loginOpen
        onClosed: app.loginOpen = false
    }

    ListenTogetherDialog { id: togetherDialog }

    SongArtistsDialog { id: songArtistsDialog }

    // New-version dialog: the host's startup check sets player.updateAvailable when a
    // newer GitHub release exists; the update button downloads the APK in-app (through
    // the mirror) and hands it to the system installer.
    Dialog {
        id: updateDialog
        title: "发现新版本"
        icon: "system_update"
        text: "新版本 " + player.updateVersion + " 现已发布"
        acceptText: "立即更新"
        rejectText: "稍后"
        onAccepted: player.startUpdateDownload()

        Flickable {
            width: parent.width
            height: Math.min(notesText.height, 260)
            contentHeight: notesText.height
            clip: true
            Text {
                id: notesText
                width: parent.width
                text: player.updateNotes
                color: Theme.color.onSurfaceVariantColor
                fontSize: 13
                wrapMode: Text.Wrap
            }
        }
    }

    Dialog {
        id: graphicsFallbackDialog
        title: "图形后端回退"
        icon: "warning"
        text: "Vulkan 图形后端初始化失败，QPlayer 已自动使用 OpenGL 继续运行，并已更新设置。"
        acceptText: "知道了"
        showRejectButton: false
        Component.onCompleted: {
            if (settings.graphicsFallbackNotice) graphicsFallbackDialog.open()
        }
    }

    Dialog {
        id: credentialNoticeDialog
        title: player.credentialNoticeType === 1
            ? "登录凭据保护已启用"
            : (player.credentialNoticeType === 2
                ? "系统密钥库不可用" : "无法读取登录凭据")
        icon: player.credentialNoticeType === 1 ? "verified_user" : "warning"
        text: player.credentialNoticeType === 1
            ? "您的登录凭据已加密，并由系统密钥库保护。"
            : (player.credentialNoticeType === 2
                ? "无法使用系统密钥库，登录凭据已回退到仅当前用户可读的本地密钥保护。此模式的安全性低于系统密钥库，请确保本机账户和文件权限安全。"
                : "系统密钥库未能及时返回解密密钥，可能尚未解锁。QPlayer 已中断凭据恢复以避免阻塞启动，现有密文和密钥均未被重置。请先解锁系统密钥库（Linux 上为 KWallet/Keyring）后重试；也可以清除旧凭据后重新登录并继续使用系统加密，或回退普通加密。")
        rejectText: "回退普通加密"
        rejectIcon: player.credentialNoticeType === 3 ? "warning" : ""
        showRejectButton: player.credentialNoticeType === 3
        neutralText: "重新登录并加密"
        showNeutralButton: player.credentialNoticeType === 3
        closeOnScrim: player.credentialNoticeType !== 3
        acceptText: player.credentialNoticeType === 3 ? "重试" : "知道了"
        onAccepted: {
            if (player.credentialNoticeType === 3) player.retryCredentialUnlock()
        }
        onRejected: {
            if (player.credentialNoticeType === 3) {
                fallbackConfirmOpenTimer.restart()
            }
        }
        onNeutral: {
            if (player.credentialNoticeType === 3) player.prepareEncryptedRelogin()
        }
    }

    Dialog {
        id: credentialReloginUnavailableDialog
        title: "系统密钥库仍不可用"
        icon: "warning"
        text: "QPlayer 无法在登录前访问系统密钥库，因此没有清除现有登录凭据，也没有进入登录界面。请先解锁系统密钥库（Linux 上为 KWallet/Keyring），返回后再重试。"
        acceptText: "返回"
        showRejectButton: false
        closeOnScrim: false
        onAccepted: credentialNoticeRestoreTimer.restart()
    }

    Dialog {
        id: credentialFallbackConfirmDialog
        title: "确认回退普通加密"
        icon: "warning"
        text: "系统密钥库当前无法解锁现有登录凭据。继续后，这份不可解密的登录凭据将被清除，QPlayer 会永久切换为仅当前用户可读的本地密钥保护；其安全性低于系统密钥库。"
        acceptText: "继续回退"
        rejectText: "取消"
        closeOnScrim: false
        onAccepted: {
            if (player.fallbackCredentialsToOwnerOnly()) {
                fallbackLoginOpenTimer.restart()
            }
        }
        onRejected: credentialNoticeRestoreTimer.restart()
    }

    // Dialog emits accepted/rejected before its 100 ms exit animation finishes.
    // Delay the next modal so two full-screen scrims never race for the same root.
    Timer {
        id: fallbackConfirmOpenTimer
        interval: 130
        repeat: false
        onTriggered: credentialFallbackConfirmDialog.open()
    }
    Timer {
        id: credentialNoticeRestoreTimer
        interval: 130
        repeat: false
        onTriggered: credentialNoticeDialog.open()
    }
    Timer {
        id: fallbackLoginOpenTimer
        interval: 130
        repeat: false
        onTriggered: app.loginOpen = true
    }
    Timer {
        id: encryptedReloginOpenTimer
        interval: 130
        repeat: false
        onTriggered: app.loginOpen = true
    }
    Timer {
        id: encryptedReloginUnavailableOpenTimer
        interval: 130
        repeat: false
        onTriggered: credentialReloginUnavailableDialog.open()
    }

    property real credentialReloginWatch: player.credentialReloginRevision
    onCredentialReloginWatchChanged: {
        if (player.credentialReloginRevision <= 0) return
        if (player.credentialReloginResult === 1) encryptedReloginOpenTimer.restart()
        else encryptedReloginUnavailableOpenTimer.restart()
    }

    property real credentialNoticeWatch: player.credentialNoticeRevision
    onCredentialNoticeWatchChanged: {
        if (player.credentialNoticeRevision > 0) credentialNoticeDialog.open()
    }

    property bool graphicsFallbackWatch: settings.graphicsFallbackNotice
    onGraphicsFallbackWatchChanged: {
        if (settings.graphicsFallbackNotice) graphicsFallbackDialog.open()
    }

    property bool updateWatch: player.updateAvailable
    onUpdateWatchChanged: if (player.updateAvailable) updateDialog.open()

    // In-app update download progress, driven by the host (-1 idle, 0..100, -2 fail).
    property int updateProgWatch: player.updateProgress
    onUpdateProgWatchChanged: if (player.updateProgress === -2) app.showToast("更新下载失败，请稍后重试")

    Rectangle {
        visible: player.updateProgress >= 0 && player.updateProgress < 100
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.rightMargin: settings.rightInset
        anchors.bottom: parent.bottom
        height: 48 + settings.bottomInset
        color: Theme.color.surfaceContainerHigh
        z: 9000
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 14
            text: "正在下载更新… " + player.updateProgress + "%"
            color: Theme.color.onSurfaceColor
            fontSize: 14
        }
    }

    ToastStack {
        id: snack
        anchors.left: rail.right
        anchors.right: parent.right
        anchors.leftMargin: 16
        anchors.rightMargin: settings.rightInset + 16
        z: 20000
    }

    // Windows-only custom title bar (see TitleBar.qml / WinFrameless.java /
    // WindowChrome.java). hostWindow is registered on EVERY platform (a real,
    // functional WindowChrome on Windows desktop, a no-op WindowChromeStub
    // everywhere else -- Android's QmlGLSurfaceView and DesktopWindow both
    // register it unconditionally) precisely so this component can gate purely
    // on hostWindow.available rather than the identifier's mere existence --
    // qml4j's compiler rejects an undeclared top-level identifier at compile
    // time, even inside a typeof guard on a branch that never runs, so
    // hostWindow being simply absent on some platforms is not an option here.
    // High z so its caption buttons stay click-priority-correct over any
    // current/future full-window overlay -- the shared Theme.color.surface
    // token underneath means z-order never affects visual seamlessness, only
    // click routing.
    TitleBar {
        // A cold-start qml4j quirk (same general class as the Tabs indicator's
        // documented cold-start settle issue) left this reserved top strip painted
        // as only a few stray px on the very first frames, indefinitely, regardless
        // of anchors vs plain x/y/width positioning -- reproduced down to a bare
        // colored Rectangle with none of this component's own logic. The real fix
        // is DesktopWindow.nudgeResizeOnce(), a one-time real WM_SIZE round trip
        // right after the first frame shows, which reliably un-sticks it.
        x: 0
        y: 0
        width: parent.width
        // The lyric page is fully immersive on desktop: the custom bar hides while
        // it's open so LyricOverlay's own three title buttons can sit flush at the
        // top (see LyricOverlay.topPad). Symmetric with LyricOverlay.visible.
        visible: hostWindow.available && !(player.lyricSlide > 0.001)
        height: settings.topInset
        z: 10000
    }

    // --- debug log overlay ---------------------------------------------
    Rectangle {
        visible: app.showLog
        anchors.fill: parent
        color: Theme.color.surfaceContainerHighest

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                Layout.margins: 8
                spacing: 4
                Text {
                    Layout.fillWidth: true
                    text: "日志"
                    color: Theme.color.onSurfaceColor
                    fontSize: 18
                }
                IconButton { type: "standard"; icon: "delete"; onClicked: player.clearLog() }
                IconButton { type: "standard"; icon: "close"; onClicked: app.showLog = false }
            }

            Flickable {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.margins: 12
                clip: true
                contentHeight: logText.height
                Text {
                    id: logText
                    width: parent.width
                    text: player.logText
                    color: Theme.color.onSurfaceColor
                    fontSize: 12
                    wrapMode: Text.WrapAnywhere
                }
            }
        }
    }
}
