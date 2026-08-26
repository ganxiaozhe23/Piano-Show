from __future__ import annotations

"""Small optional PySide6 front-end for the same deterministic compiler used by the CLI."""

from pathlib import Path

from .compiler import compile_to_file
from .models import CompileOptions


def run() -> int:
    try:
        from PySide6.QtWidgets import (
            QApplication,
            QFileDialog,
            QFormLayout,
            QLabel,
            QLineEdit,
            QMainWindow,
            QMessageBox,
            QPushButton,
            QSpinBox,
            QVBoxLayout,
            QWidget,
        )
    except ImportError as error:
        raise SystemExit("GUI requires the optional dependency: pip install 'piano-show[gui]'") from error

    class Window(QMainWindow):
        def __init__(self) -> None:
            super().__init__()
            self.setWindowTitle("Piano Show 编排器")
            self.resize(520, 280)
            self.midi = QLineEdit()
            self.image = QLineEdit()
            self.output = QLineEdit("show.pshow")
            self.resolution = QSpinBox()
            self.resolution.setRange(1, 1024)
            self.resolution.setValue(128)
            form = QFormLayout()
            for label, field, filter_text in (
                ("MIDI", self.midi, "MIDI (*.mid *.midi)"),
                ("图片", self.image, "图片 (*.png *.jpg *.jpeg *.webp)"),
                ("输出", self.output, "PShow (*.pshow)"),
            ):
                button = QPushButton("浏览…")
                button.clicked.connect(lambda _checked=False, f=field, filt=filter_text: self._browse(f, filt))
                row = QVBoxLayout()
                row.addWidget(field)
                row.addWidget(button)
                form.addRow(QLabel(label), row)
            form.addRow("分辨率", self.resolution)
            compile_button = QPushButton("编译 .pshow")
            compile_button.clicked.connect(self._compile)
            root = QWidget()
            layout = QVBoxLayout(root)
            layout.addLayout(form)
            layout.addWidget(compile_button)
            self.setCentralWidget(root)

        @staticmethod
        def _browse(field: QLineEdit, filter_text: str) -> None:
            file_name, _ = QFileDialog.getOpenFileName(None, "选择文件", "", filter_text)
            if file_name:
                field.setText(file_name)

        def _compile(self) -> None:
            try:
                options = CompileOptions(resolution=self.resolution.value())
                result = compile_to_file(self.midi.text(), self.image.text(), self.output.text(), options)
                QMessageBox.information(self, "编译完成", f"已生成 {self.output.text()}\n事件：{len(result.events)}\n像素：{len(result.pixels)}")
            except Exception as error:  # GUI should show actionable errors instead of crashing.
                QMessageBox.critical(self, "编译失败", str(error))

    app = QApplication.instance() or QApplication([])
    window = Window()
    window.show()
    return app.exec()
