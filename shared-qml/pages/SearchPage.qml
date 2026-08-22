import QtQuick
import QtQuick.Layouts
import md3.Core
import "."
import "../components"

// 搜索页：空输入显示搜索历史 + 热门搜索，输入时实时搜索，结果可点击播放。
Item {
    id: page
    // 0 = 折叠(5条), 1 = 展开(30条), 2 = 展开(70条), 3 = 展开全部(100条)
    property int historyExpandLevel: 0

    // Coalesce rapid IME edits into one network/local/custom search. Previously
    // every individual composition update synchronously filtered the full local
    // library and also queued two network searches, which could stall the render
    // thread and retain many obsolete result/cover generations after repeated use.
    Timer {
        id: searchDebounce
        interval: 350
        repeat: false
        onTriggered: page.runSearch(false)
    }

    function runSearch(addHistory) {
        var text = query.text
        if (text.length === 0) return
        player.search(text)
        player.searchLocal(text)
        player.searchCustom(text)
        if (addHistory) player.addSearchHistory(text)
    }

    function runSearchNow(addHistory) {
        searchDebounce.stop()
        runSearch(addHistory)
    }

    Component.onCompleted: player.loadHotSearches()

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: 12
            spacing: 4

            TextField {
                id: query
                Layout.fillWidth: true
                Layout.alignment: Qt.AlignVCenter
                type: "filled"
                leadingIcon: "search"
                label: "搜索歌曲"
                // Real-time search on every keystroke. searchLocal is a synchronous
                // in-memory filter, but large libraries still make it expensive enough
                // to debounce together with the two network sources.
                onTextChanged: {
                    if (text.length > 0) searchDebounce.restart()
                    else { searchDebounce.stop(); page.historyExpandLevel = 0 }
                }
                onAccepted: {
                    if (query.text.length > 0) page.runSearchNow(true)
                }
            }
            IconButton {
                Layout.alignment: Qt.AlignVCenter
                type: "filled"; icon: "search"
                onClicked: {
                    if (query.text.length > 0) page.runSearchNow(true)
                }
            }
        }

        // --- History + Hot searches (shown when input is empty) ---
        // Explicit index-positioned rows in a plain Item, NOT a Column positioner:
        // qml4j lays Repeater delegates out by their own x/y, it does not flow
        // dynamically-created children through a positioner (same idiom as HomePage /
        // VirtualSongList). A Column here left the rows unpositioned/zero-width.
        Item {
            id: hotArea
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length === 0

            property int rowH: 44
            // 分段展开: 5条(折叠) -> 30条 -> 70条 -> 100条(全部)
            property int collapsedCount: 5
            property int firstExpandCount: 30
            property int secondExpandCount: 70
            property int fullCount: 100
            property int histCount: player.searchHistory ? player.searchHistory.length : 0
            // 纯三元表达式而非 { ... } block：qml4j 对 block 属性绑定兼容性差，
            // block 绑定失败会导致 displayCount 失效、布局高度算错。
            property int displayCount: histCount === 0 ? 0 : (page.historyExpandLevel === 0 ? Math.min(collapsedCount, histCount) : (page.historyExpandLevel === 1 ? Math.min(firstExpandCount, histCount) : (page.historyExpandLevel === 2 ? Math.min(secondExpandCount, histCount) : Math.min(fullCount, histCount))))
            property int hotCount: player.hotSearches ? player.hotSearches.length : 0
            property bool hasHistory: player.searchHistory && player.searchHistory.length > 0
            // 显示展开/收起按钮的条件：有超过 5 条历史记录
            property bool showExpandToggle: histCount > collapsedCount

            // section y-offsets (explicit, no Column)
            property int histHeaderY: hasHistory ? 16 : 0
            property int histHeaderH: hasHistory ? 48 : 0
            property int histRowsY: histHeaderY + histHeaderH
            property int histRowsH: displayCount * rowH
            property int expandY: histRowsY + histRowsH
            property int expandH: showExpandToggle ? 40 : 0
            property int dividerY: expandY + expandH + (hasHistory && hotCount > 0 ? 8 : 0)
            property int dividerH: hasHistory && hotCount > 0 ? 1 : 0
            property int hotHeaderY: dividerY + dividerH + (hotCount > 0 ? 8 : 0)
            property int hotHeaderH: hotCount > 0 ? 40 : 0
            property int hotRowsY: hotHeaderY + hotHeaderH
            property int totalH: hotRowsY + hotCount * rowH + 16

            Flickable {
                anchors.fill: parent
                clip: true
                contentWidth: width
                contentHeight: hotArea.totalH

                // --- History header ---
                Item {
                    x: 16; y: hotArea.histHeaderY
                    width: hotArea.width - 32
                    height: hotArea.histHeaderH
                    visible: hotArea.hasHistory

                    Text {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        text: "搜索历史"
                        font.pixelSize: 18
                        font.weight: Font.DemiBold
                        color: Theme.color.onSurfaceColor
                    }
                    IconButton {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        type: "standard"; icon: "delete_sweep"
                        onClicked: player.clearSearchHistory()
                    }
                }

                // --- History rows ---
                Item {
                    x: 16; y: hotArea.histRowsY
                    width: hotArea.width - 32
                    height: hotArea.histRowsH

                    Repeater {
                        model: hotArea.displayCount

                        Item {
                            width: hotArea.width - 32
                            height: hotArea.rowH
                            y: index * hotArea.rowH

                            Rectangle {
                                anchors.fill: parent
                                radius: 8
                                color: rowMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                            }
                            Text {
                                x: 12
                                anchors.verticalCenter: parent.verticalCenter
                                text: "history"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 20
                                color: Theme.color.onSurfaceVariantColor
                            }
                            Text {
                                x: 44; width: parent.width - 44 - 44
                                anchors.verticalCenter: parent.verticalCenter
                                text: player.searchHistory && player.searchHistory[index] ? player.searchHistory[index] : ""
                                font.pixelSize: 15
                                color: Theme.color.onSurfaceColor
                                elide: Text.ElideRight
                            }
                            Text {
                                x: parent.width - 40; width: 40
                                anchors.verticalCenter: parent.verticalCenter
                                horizontalAlignment: Text.AlignHCenter
                                text: "close"
                                font.family: Theme.iconFont.name
                                font.pixelSize: 18
                                color: Theme.color.onSurfaceVariantColor
                            }
                            MouseArea {
                                id: rowMA
                                anchors.fill: parent
                                onClicked: {
                                    if (mouseX >= parent.width - 44) {
                                        player.removeSearchHistory(index)
                                    } else {
                                        var kw = player.searchHistory && player.searchHistory[index] ? player.searchHistory[index] : ""
                                        if (kw.length > 0) {
                                            query.text = kw
                                            page.runSearchNow(true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Expand / collapse buttons ---
                Item {
                    x: 16; y: hotArea.expandY
                    width: hotArea.width - 32; height: hotArea.expandH
                    visible: hotArea.showExpandToggle

                    // 收起按钮：level >= 1 时显示；中间等级(1/2)且还有更多可展开时与展开按钮各占一半
                    Rectangle {
                        visible: page.historyExpandLevel >= 1
                        anchors.left: parent.left
                        width: page.historyExpandLevel >= 1 && page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount) ? parent.width / 2 - 4 : parent.width
                        height: parent.height
                        radius: 8
                        color: collapseMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                        Text {
                            anchors.centerIn: parent
                            text: "收起"
                            font.pixelSize: 14; color: Theme.color.primary
                        }
                        MouseArea {
                            id: collapseMA
                            anchors.fill: parent
                            onClicked: page.historyExpandLevel = 0
                        }
                    }

                    // 展开更多按钮：level <= 2 且仍有更多时显示
                    Rectangle {
                        visible: page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 0 ? hotArea.collapsedCount : (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount))
                        anchors.right: parent.right
                        width: page.historyExpandLevel >= 1 && page.historyExpandLevel <= 2 && hotArea.histCount > (page.historyExpandLevel === 1 ? hotArea.firstExpandCount : hotArea.secondExpandCount) ? parent.width / 2 - 4 : parent.width
                        height: parent.height
                        radius: 8
                        color: expandMA.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                        Text {
                            anchors.centerIn: parent
                            text: "展开更多"
                            font.pixelSize: 14; color: Theme.color.primary
                        }
                        MouseArea {
                            id: expandMA
                            anchors.fill: parent
                            onClicked: {
                                if (page.historyExpandLevel === 0) page.historyExpandLevel = 1
                                else if (page.historyExpandLevel === 1) page.historyExpandLevel = 2
                                else if (page.historyExpandLevel === 2) page.historyExpandLevel = 3
                            }
                        }
                    }
                }

                // --- Divider ---
                Rectangle {
                    x: 16; y: hotArea.dividerY
                    width: hotArea.width - 32; height: hotArea.dividerH
                    color: Theme.color.outlineVariant
                    visible: hotArea.dividerH > 0
                }

                // --- Hot searches header ---
                Text {
                    x: 16; y: hotArea.hotHeaderY
                    text: "热门搜索"
                    font.pixelSize: 18; font.weight: Font.DemiBold
                    color: Theme.color.onSurfaceColor
                    visible: hotArea.hotCount > 0
                }

                // --- Hot search rows ---
                Item {
                    x: 0; y: hotArea.hotRowsY
                    width: hotArea.width
                    height: hotArea.hotCount * hotArea.rowH

                    Repeater {
                        model: player.hotSearches

                        Item {
                            width: hotArea.width
                            height: hotArea.rowH
                            y: index * hotArea.rowH

                            Rectangle {
                                anchors.fill: parent
                                anchors.leftMargin: 12; anchors.rightMargin: 12
                                radius: 8
                                color: hma.pressed ? Theme.color.surfaceContainerHigh : "transparent"
                            }

                            Row {
                                anchors.left: parent.left; anchors.leftMargin: 24
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 8

                                Text {
                                    text: "search"
                                    font.family: Theme.iconFont.name; font.pixelSize: 20
                                    color: Theme.color.onSurfaceVariantColor
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                                Text {
                                    text: modelData || ""
                                    font.pixelSize: 15; color: Theme.color.onSurfaceColor
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                            }

                            MouseArea {
                                id: hma; anchors.fill: parent
                                onClicked: {
                                    query.text = modelData
                                    page.runSearchNow(true)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Search results (shown when input is not empty) ---
        // One unified, always-scrollable list (网易云 first, then 本地, then
        // 自定义源 — player.searchRows is built in that order by
        // PlayerController.rebuildSearchRows()) instead of three independently
        // height-managed VirtualSongLists: those fought each other for space in
        // qml4j's ColumnLayout (which hands a fillHeight child whatever room is
        // left after already-placed siblings rather than pre-reserving room for
        // every sibling like real Qt does), squeezing whichever section came
        // after the fillHeight one down to nothing under a short window.
        //
        // No per-row context menu here ("加入播放列表/复制链接" are built around
        // neteaseId) — this list mixes netease/local/custom rows, so it drops
        // the menu uniformly rather than threading per-kind eligibility through
        // VirtualSongList/SongRow. Still available via Home/Playlist pages.
        VirtualSongList {
            id: unifiedResults
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: query.text.length > 0
            list: player.searchRows
            onActivated: player.playSearchRow(unifiedResults.activatedIndex)
        }
    }
}
