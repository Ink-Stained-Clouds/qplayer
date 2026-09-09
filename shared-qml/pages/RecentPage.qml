import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// Recent: the active source plugin's listening history (signed in).
Item {
    id: page
    signal requestLogin()

    VirtualSongList {
        id: recent
        anchors.fill: parent
        visible: player.loggedIn
        // Same guard as LocalPage/QueuePage: this page is always in the tree, so a
        // bare bind keeps a SongRow per history entry alive while Recent is hidden.
        list: page.visible ? (player.sourceContentActive
                              ? player.sourceRecentSongs : player.recentSongs) : null
        onActivated: player.playRecentSong(recent.activatedIndex)
    }

    ColumnLayout {
        anchors.centerIn: parent
        spacing: 12
        visible: !player.loggedIn
        Text {
            Layout.alignment: Qt.AlignHCenter
            text: i18n.t("recent.signInPrompt")
            color: Theme.color.onSurfaceVariantColor
            fontSize: 15
        }
        Button {
            Layout.alignment: Qt.AlignHCenter
            type: "filled"; text: i18n.t("recent.signInButton")
            onClicked: page.requestLogin()
        }
    }
}
