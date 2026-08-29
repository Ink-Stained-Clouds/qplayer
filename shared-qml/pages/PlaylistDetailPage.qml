import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// Drill-in playlist view: header with back + title, then the tracks.
Rectangle {
    id: page
    signal back()
    color: Theme.color.surface

    // Reset the scroll to the top whenever a new playlist starts loading, so the
    // previous playlist's scroll position doesn't carry over.
    property bool loadingWatch: player.playlistLoading
    onLoadingWatchChanged: if (player.playlistLoading) tracks.contentY = 0

    // Swallow taps on empty areas so they don't reach the page beneath.
    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // Custom header: qml4j can't set a sub-property of TopAppBar's
        // navigationIcon alias (navigationIcon.icon) via grouped binding.
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 64
            Layout.leftMargin: 4
            Layout.rightMargin: 16
            spacing: 4
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"; icon: "arrow_back"
                onClicked: page.back()
            }
            Item {
                id: titleSlot
                property bool compactLayout: page.width < 600
                // Layout.fillHeight (not an explicit Layout.preferredHeight +
                // Layout.alignment combo) so the header row's real 64px height
                // is this Item's own box directly -- qml4j's height/alignment
                // propagation through a nested Layout.preferredHeight isn't
                // reliable (same class of bug SearchPage's merged search bar
                // hit this session), which left some playlists' single-line
                // titles sitting off-center instead of vertically centered.
                Layout.fillWidth: true
                Layout.fillHeight: true

                // Single line either way now (narrow just uses the smaller
                // titleMedium size); overflow marquee-scrolls with faded edges
                // instead of wrapping to a second line or eliding.
                MarqueeText {
                    anchors.fill: parent
                    text: player.playlistTitle
                    textColor: Theme.color.onSurfaceColor
                    fontFamily: titleSlot.compactLayout
                                ? Theme.typography.titleMedium.family
                                : Theme.typography.titleLarge.family
                    fontSize: titleSlot.compactLayout
                              ? Theme.typography.titleMedium.size
                              : Theme.typography.titleLarge.size
                }
            }
            Button {
                Layout.alignment: Qt.AlignVCenter
                type: "text"
                visible: player.loggedIn && !player.playlistLoading
                         && player.playlistTracks && player.playlistTracks.length > 0
                enabled: !player.intelligenceLoading
                icon: "favorite"
                text: player.intelligenceLoading ? "推荐中…" : "心动推荐"
                onClicked: player.startIntelligenceMode(player.openPlaylistId)
            }
            // Collect (subscribe) this playlist. Shown only once loaded and only for
            // playlists that aren't the user's own; filled when already collected. The
            // initial state comes from playlist/detail, so it's correct on open.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && !player.playlistOwned
                icon: player.playlistSubscribed ? "bookmark" : "bookmark_border"
                contentColor: player.playlistSubscribed ? Theme.color.primary : Theme.color.onSurfaceColor
                onClicked: player.togglePlaylistSubscribe()
            }
            // Change cover — own playlists only. Both hosts install the same picker
            // callback: Android keeps its system gallery picker, while desktop opens
            // a cross-platform image file chooser.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && player.playlistOwned
                icon: "image"
                onClicked: player.pickPlaylistCover(player.openPlaylistId)
            }
            // Delete — only your own playlists, and never the "我喜欢的音乐" default
            // (the first playlist, which can't be removed). Confirms first.
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "standard"
                visible: player.loggedIn && !player.playlistLoading && player.playlistDeletable
                icon: "delete"
                onClicked: deleteDialog.open()
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            VirtualSongList {
                id: tracks
                anchors.fill: parent
                visible: !player.playlistLoading
                // Drop the row delegates when the detail page is closed (see
                // QueuePage): an invisible detail otherwise keeps the whole
                // playlist's SongRows alive after you return home.
                list: page.visible ? player.playlistTracks : null
                // Long-press a track → add to another playlist, and (in your own
                // playlist) remove it from this one. Not login-gated: "加入播放列表"
                // (local list) works signed-out too.
                songMenu: true
                ownedPlaylist: player.playlistOwned
                showOfflineBadge: player.playlistOffline
                onActivated: player.playPlaylistTrack(tracks.activatedIndex)
            }

            LoadingIndicator {
                anchors.centerIn: parent
                visible: player.playlistLoading
                running: player.playlistLoading
                withContainer: true
                size: 56
            }
        }
    }

    // Delete confirmation. On accept the controller removes it and refreshes 我的;
    // we drill back out since this playlist no longer exists.
    Dialog {
        id: deleteDialog
        icon: "delete"
        title: "删除歌单"
        text: "确定删除歌单「" + player.playlistTitle + "」吗？此操作无法撤销。"
        acceptText: "删除"
        rejectText: "取消"
        onAccepted: {
            player.deletePlaylist(player.openPlaylistId)
            page.back()
        }
    }

}
