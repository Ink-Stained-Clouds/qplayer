import QtQuick

// One route-backed full-screen page. PageStack logic stays in Main.qml, while
// this component owns the repeated painting, z-order and transition policy:
// forward keeps the underlay static; Back keeps the restored page static and
// animates only the departing top page; replace hides the old path immediately.
Loader {
    id: pageLoader

    required property var pageManager
    required property string routeType
    property string transitionAxis: "x"
    property real hiddenOffset: transitionAxis === "x" ? 36 : 32
    property bool present: pageManager.pageDepth(routeType) > 0
    property bool animateChange: present || pageManager.navigationDirection === "back"

    width: parent ? parent.width : 0
    height: parent ? parent.height : 0
    visible: pageManager.pagePainted(routeType, opacity)
    enabled: pageManager.currentOverlay === routeType
    z: pageManager.pageLayer(routeType, opacity)
    opacity: present ? 1 : 0
    x: transitionAxis === "x" ? (present ? 0 : hiddenOffset) : 0
    y: transitionAxis === "y" ? (present ? 0 : hiddenOffset) : 0

    Behavior on opacity {
        enabled: pageLoader.animateChange
        NumberAnimation { duration: 200; easing.type: Easing.OutCubic }
    }
    Behavior on x {
        enabled: pageLoader.transitionAxis === "x" && pageLoader.animateChange
        NumberAnimation { duration: 260; easing.type: Easing.OutCubic }
    }
    Behavior on y {
        enabled: pageLoader.transitionAxis === "y" && pageLoader.animateChange
        NumberAnimation { duration: 260; easing.type: Easing.OutCubic }
    }
}
