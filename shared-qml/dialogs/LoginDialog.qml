import QtQuick
import QtQuick.Layouts
import md3.Core

// Three login paths share the same transactional Cookie importer in
// PlayerController: QR, the official site in a system WebView, and a manual
// Cookie-header fallback. Network and credential persistence stay off-render.
Rectangle {
    id: dialog

    property bool active: false
    property int loginMode: 0 // 0 QR, 1 official website, 2 pasted Cookie
    property bool ready: false
    property string cookieText: ""
    property var successRevision: player.webLoginSuccessRevision
    signal closed()

    anchors.fill: parent
    opacity: active ? 1 : 0
    visible: opacity > 0.01
    color: "#99000000"
    Behavior on opacity { NumberAnimation { duration: 150 } }

    onActiveChanged: {
        if (active) {
            player.clearWebLoginError();
            loginMode = player.pluginLoginActive && !player.pluginQrLoginAvailable
                        ? (player.webLoginAvailable ? 1 : 2) : 0;
            cookieText = "";
            ready = false;
            if (player.pluginLoginActive) player.startQrLogin();
            revealTimer.restart();
        }
    }
    onLoginModeChanged: {
        player.clearWebLoginError();
        if (active && loginMode === 0) {
            ready = false;
            player.startQrLogin();
            revealTimer.restart();
        }
    }
    onSuccessRevisionChanged: if (active) dialog.closed()

    function statusText(code) {
        if (code === 0) return i18n.t("login.qr.loading");
        if (code === 802) return i18n.t("login.qr.confirm");
        if (code === 803) return i18n.t("login.qr.done");
        if (code === 800) return i18n.t("login.qr.expired");
        return i18n.t("login.qr.scan", player.loginProviderName);
    }

    Timer {
        id: revealTimer
        interval: 280
        onTriggered: { dialog.ready = true; qrCanvas.requestPaint(); }
    }
    property var qr: player.qrImage
    onQrChanged: if (ready) qrCanvas.requestPaint()
    property int st: player.qrStatus
    onStChanged: if (st === 803) dialog.closed()

    MouseArea { anchors.fill: parent }

    Timer {
        interval: 800
        repeat: true
        running: dialog.active && player.pluginLoginActive && dialog.loginMode === 0
        onTriggered: player.pollQrLogin()
    }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(420, parent.width - 32)
        height: Math.min(480, parent.height - 32)
        radius: 24
        color: Theme.color.surfaceContainerHigh
        clip: true
        scale: dialog.active ? 1 : 0.9
        Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 14

            Text {
                Layout.alignment: Qt.AlignHCenter
                text: i18n.t("login.title", player.loginProviderName)
                color: Theme.color.onSurfaceColor
                fontSize: 20
            }

            Text {
                Layout.fillWidth: true
                Layout.fillHeight: true
                visible: !player.pluginLoginActive
                text: i18n.t("login.unsupported")
                wrapMode: Text.WordWrap
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
                color: Theme.color.onSurfaceVariantColor
                fontSize: 14
            }

            SegmentedButton {
                visible: player.pluginLoginActive
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                selectedIndex: dialog.loginMode
                buttons: [
                    { text: i18n.t("login.mode.qr"), selected: dialog.loginMode === 0,
                      enabled: !player.pluginLoginActive || player.pluginQrLoginAvailable },
                    { text: i18n.t("login.mode.web"), selected: dialog.loginMode === 1,
                      enabled: player.webLoginAvailable },
                    { text: "Cookie", selected: dialog.loginMode === 2,
                      enabled: !player.pluginLoginActive || player.pluginCredentialLoginAvailable }
                ]
                onClicked: (index) => dialog.loginMode = index
            }

            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true
                visible: player.pluginLoginActive && dialog.loginMode === 0

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 12

                    Rectangle {
                        Layout.alignment: Qt.AlignHCenter
                        width: 220
                        height: 220
                        radius: 12
                        color: "#ffffff"

                        CircularProgress {
                            anchors.centerIn: parent
                            width: 48; height: 48
                            indeterminate: true
                            visible: dialog.active && !qrCanvas.visible
                        }
                        Canvas {
                            id: qrCanvas
                            anchors.centerIn: parent
                            width: 200; height: 200
                            visible: dialog.ready && dialog.qr.length > 0
                            onPaint: {
                                var ctx = getContext("2d");
                                if (!dialog.ready) return;
                                var matrix = dialog.qr;
                                if (!matrix || matrix.length <= 0) return;
                                ctx.fillStyle = "#ffffff";
                                ctx.fillRect(0, 0, width, height);
                                var size = matrix.length;
                                var cell = width / size;
                                ctx.fillStyle = "#000000";
                                for (var y = 0; y < size; y++) {
                                    var row = matrix[y];
                                    for (var x = 0; x < size; x++) {
                                        if (row[x]) ctx.fillRect(
                                            Math.floor(x * cell), Math.floor(y * cell),
                                            Math.ceil(cell), Math.ceil(cell));
                                    }
                                }
                            }
                        }
                    }

                    Text {
                        Layout.fillWidth: true
                        text: dialog.statusText(player.qrStatus)
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    Item { Layout.fillHeight: true }
                }
            }

            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true
                visible: player.pluginLoginActive && dialog.loginMode === 1

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 16
                    Item { Layout.fillHeight: true }
                    Text {
                        Layout.fillWidth: true
                        text: player.loginWebInstructions
                        wrapMode: Text.WordWrap
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 14
                    }
                    Button {
                        Layout.alignment: Qt.AlignHCenter
                        type: "filled"
                        icon: "open_in_new"
                        text: i18n.t(player.webLoginBusy ? "login.web.waiting" : "login.web.open")
                        enabled: !player.webLoginBusy
                        onClicked: player.startWebLogin()
                    }
                    Text {
                        Layout.fillWidth: true
                        visible: player.webLoginError.length > 0
                        text: player.webLoginError
                        wrapMode: Text.WordWrap
                        horizontalAlignment: Text.AlignHCenter
                        color: Theme.color.error
                        fontSize: 13
                    }
                    Item { Layout.fillHeight: true }
                }
            }

            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true
                visible: player.pluginLoginActive && dialog.loginMode === 2

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 12
                    Item { Layout.fillHeight: true }
                    Text {
                        Layout.fillWidth: true
                        text: player.loginCredentialInstructions
                        wrapMode: Text.WordWrap
                        color: Theme.color.onSurfaceVariantColor
                        fontSize: 13
                    }
                    TextField {
                        Layout.fillWidth: true
                        type: "outlined"
                        label: player.loginCredentialLabel
                        isPassword: true
                        text: dialog.cookieText
                        errorText: player.webLoginError
                        onTextChanged: dialog.cookieText = text
                        onAccepted: if (text.length > 0 && !player.webLoginBusy)
                            player.submitCookieLogin(text)
                    }
                    Button {
                        Layout.fillWidth: true
                        type: "filled"
                        text: i18n.t(player.webLoginBusy ? "login.web.verifying" : "login.web.verify")
                        enabled: dialog.cookieText.trim().length > 0 && !player.webLoginBusy
                        onClicked: player.submitCookieLogin(dialog.cookieText)
                    }
                    Item { Layout.fillHeight: true }
                }
            }

            Button {
                Layout.alignment: Qt.AlignHCenter
                type: "text"
                text: i18n.t("common.cancel")
                enabled: !player.webLoginBusy
                onClicked: {
                    player.cancelWebLogin();
                    dialog.closed();
                }
            }
        }
    }
}
