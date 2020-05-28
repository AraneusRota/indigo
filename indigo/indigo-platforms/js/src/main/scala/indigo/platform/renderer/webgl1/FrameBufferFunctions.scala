package indigo.platform.renderer.webgl1

import indigo.shared.ClearColor
import org.scalajs.dom.raw.WebGLRenderingContext
import org.scalajs.dom.raw.WebGLRenderingContext._
import org.scalajs.dom.raw.{WebGLFramebuffer, WebGLTexture}
import indigo.platform.renderer.shared.WebGLHelper

object FrameBufferFunctions {

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def createAndSetupTexture(gl: WebGLRenderingContext, width: Int, height: Int): WebGLTexture = {
    val texture = WebGLHelper.createAndBindTexture(gl)

    gl.texImage2D(TEXTURE_2D, 0, RGBA, width, height, 0, RGBA, UNSIGNED_BYTE, null)

    texture
  }

  def createFrameBuffer(gl: WebGLRenderingContext, texture: WebGLTexture): FrameBufferComponents = {
    val frameBuffer = gl.createFramebuffer()

    gl.bindFramebuffer(FRAMEBUFFER, frameBuffer)
    gl.framebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, texture, 0)

    FrameBufferComponents(frameBuffer, texture)
  }

  def switchToFramebuffer(gl: WebGLRenderingContext, frameBuffer: WebGLFramebuffer, clearColor: ClearColor): Unit = {
    gl.bindFramebuffer(FRAMEBUFFER, frameBuffer)
    gl.clearColor(clearColor.r, clearColor.g, clearColor.b, clearColor.a)
    gl.clear(COLOR_BUFFER_BIT)
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def switchToCanvas(gl: WebGLRenderingContext, clearColor: ClearColor): Unit = {
    gl.bindFramebuffer(FRAMEBUFFER, null)
    gl.clearColor(clearColor.r, clearColor.g, clearColor.b, clearColor.a)
    gl.clear(COLOR_BUFFER_BIT)
  }
}

final class FrameBufferComponents(val frameBuffer: WebGLFramebuffer, val texture: WebGLTexture)
object FrameBufferComponents {
  def apply(frameBuffer: WebGLFramebuffer, texture: WebGLTexture): FrameBufferComponents =
    new FrameBufferComponents(frameBuffer, texture)
}
