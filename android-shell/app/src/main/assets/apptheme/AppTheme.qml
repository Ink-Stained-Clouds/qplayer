pragma Singleton
import QtQuick

QtObject {
    property QtObject color: QtObject {
        property color primary: "#6750a4"
        property color secondary: "#9a82db"
        property color surface: "#1b1b22"
        property color onSurface: "#e6e1e5"
    }
    property QtObject typography: QtObject {
        property int titleSize: 20
        property int bodySize: 15
    }
    property int padding: 16
}
