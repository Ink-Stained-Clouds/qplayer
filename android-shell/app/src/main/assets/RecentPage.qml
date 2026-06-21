import QtQuick
import QtQuick.Layouts
import md3.Core
import "."

// 最近播放：登录时从服务器同步，未登录时显示本地缓存。
Item {
    id: page
    signal requestLogin()

    VirtualSongList {
        id: recent
        anchors.fill: parent
        visible: player.recentSongs.length > 0
        list: player.recentSongs
        addable: true
        onActivated: player.playRecentSong(recent.activatedIndex)
        onAddRequested: player.addRecentSongToQueue(recent.addIndex)
    }

    ColumnLayout {
        anchors.centerIn: parent
        spacing: 12
        visible: player.recentSongs.length === 0
        Text {
            Layout.alignment: Qt.AlignHCenter
            text: player.loggedIn ? "暂无播放记录" : "暂无播放记录"
            color: Theme.color.onSurfaceVariantColor
            fontSize: 15
        }
        Button {
            Layout.alignment: Qt.AlignHCenter
            type: "filled"; text: "扫码登录"
            visible: !player.loggedIn
            onClicked: page.requestLogin()
        }
    }
}
