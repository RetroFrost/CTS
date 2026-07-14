from __future__ import annotations

from PySide6.QtWidgets import QComboBox, QFormLayout, QLabel, QSizePolicy, QWidget

from .iteration_fixes import IteratedStudioMainWindow


class FinalStudioMainWindow(IteratedStudioMainWindow):
    """Final 0.4.0 test window with every Models form constrained to the viewport."""

    def _build_models_tab(self) -> QWidget:
        scroll = super()._build_models_tab()
        page = scroll.widget()
        if page is None:
            return scroll

        widgets = [page, *page.findChildren(QWidget)]
        for widget in widgets:
            form = widget.layout()
            if not isinstance(form, QFormLayout):
                continue
            form.setFieldGrowthPolicy(QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow)
            form.setRowWrapPolicy(QFormLayout.RowWrapPolicy.WrapLongRows)
            form.setHorizontalSpacing(8)
            form.setVerticalSpacing(6)
            for row in range(form.rowCount()):
                label_item = form.itemAt(row, QFormLayout.ItemRole.LabelRole)
                if label_item is not None and isinstance(label_item.widget(), QLabel):
                    label = label_item.widget()
                    label.setWordWrap(True)
                    label.setMinimumWidth(0)
                    label.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Preferred)

        for combo in page.findChildren(QComboBox):
            combo.setMinimumWidth(0)
            combo.setMaximumWidth(16777215)
            combo.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

        scroll.horizontalScrollBar().setValue(0)
        return scroll
