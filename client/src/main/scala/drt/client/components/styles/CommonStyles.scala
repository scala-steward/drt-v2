package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import io.kinoplan.scalajs.react.material.ui.core.colors
import io.kinoplan.scalajs.react.material.ui.core.colors.ColorPartial
import io.kinoplan.scalajs.react.material.ui.core.styles._
import scalacss.ScalaCssReactImplicits
import scalacss.internal.mutable.StyleSheet

class CommonStyle extends StyleSheet.Inline with ScalaCssReactImplicits {
  def theme: Theme = createTheme(
    options = ThemeOptions(
      typography = TypographyOptions(useNextVariants = true),
      palette = PaletteOptions(
        primary = colors.blue,
        grey = ColorPartial(`300` = "300")
      )
    )
  )
}

object DefaultCommonStyle extends CommonStyle
