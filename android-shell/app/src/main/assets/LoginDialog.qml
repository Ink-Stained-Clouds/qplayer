import QtQuick
import QtQuick.Layouts
import md3.Core

// Netease QR login. On open, fetches a login key + module matrix and draws it
// on a Canvas, then polls the scan status every 800ms (801 waiting / 802
// scanned / 803 success / 800 expired -> refresh).
Rectangle {
    id: dialog

    property bool active: false
    signal closed()

    property var qr: null
    property string status: "正在获取二维码…"

    anchors.fill: parent
    visible: active
    color: "#99000000"

    onActiveChanged: if (active) refresh(); else poll.stop()

    function refresh() {
        player.qrLoginContent();          // mints a key (stored controller-side)
        qr = player.qrMatrix();
        status = "请用网易云音乐 App 扫码";
        canvas.requestPaint();
        poll.restart();
    }

    // swallow taps on the scrim
    MouseArea { anchors.fill: parent }

    Timer {
        id: poll
        interval: 800
        repeat: true
        running: dialog.active
        onTriggered: {
            var code = player.qrLoginCheck();
            if (code === 801) dialog.status = "请用网易云音乐 App 扫码";
            else if (code === 802) dialog.status = "已扫码，请在手机上确认";
            else if (code === 803) { dialog.status = "登录成功"; poll.stop(); dialog.closed(); }
            else if (code === 800) { dialog.status = "二维码已过期，正在刷新…"; dialog.refresh(); }
        }
    }

    Rectangle {
        anchors.centerIn: parent
        width: 300
        height: 400
        radius: 24
        color: Theme.color.surfaceContainerHigh

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 16

            Text {
                Layout.alignment: Qt.AlignHCenter
                text: "扫码登录网易云"
                color: Theme.color.onSurfaceColor
                fontSize: 20
            }

            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 220; height: 220; radius: 12
                color: "#ffffff"
                Canvas {
                    id: canvas
                    anchors.centerIn: parent
                    width: 200; height: 200
                    onPaint: {
                        var ctx = getContext("2d");
                        ctx.fillStyle = "#ffffff";
                        ctx.fillRect(0, 0, width, height);
                        var m = dialog.qr;
                        if (!m || m.length <= 0) return;
                        var n = m.length;
                        var cell = width / n;
                        ctx.fillStyle = "#000000";
                        for (var y = 0; y < n; y++) {
                            var row = m[y];
                            for (var x = 0; x < n; x++) {
                                if (row[x])
                                    ctx.fillRect(Math.floor(x * cell), Math.floor(y * cell),
                                                 Math.ceil(cell), Math.ceil(cell));
                            }
                        }
                    }
                }
            }

            Text {
                Layout.alignment: Qt.AlignHCenter
                Layout.fillWidth: true
                text: dialog.status
                horizontalAlignment: Text.AlignHCenter
                color: Theme.color.onSurfaceVariantColor
                fontSize: 14
            }

            Item { Layout.fillHeight: true }

            Button {
                Layout.alignment: Qt.AlignHCenter
                type: "text"; text: "取消"
                onClicked: dialog.closed()
            }
        }
    }
}
