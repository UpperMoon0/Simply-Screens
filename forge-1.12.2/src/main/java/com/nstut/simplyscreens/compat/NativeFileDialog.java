package com.nstut.simplyscreens.compat;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
/** LWJGL2 has no tinyfd binding. Swing supplies a platform file-selection dialog. */
public final class NativeFileDialog {
 private NativeFileDialog() { }
 public static String chooseImage() {
  final String[] selection={null};
  Runnable choose=()->{ JFileChooser dialog=new JFileChooser();dialog.setDialogTitle("Select an image");dialog.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg, *.jpeg)","png","jpg","jpeg"));if(dialog.showOpenDialog(null)==JFileChooser.APPROVE_OPTION)selection[0]=dialog.getSelectedFile().getAbsolutePath(); };
  try { if(SwingUtilities.isEventDispatchThread())choose.run();else SwingUtilities.invokeAndWait(choose); }
  catch(Exception failure) { com.nstut.simplyscreens.SimplyScreens.LOGGER.error("Could not open file picker",failure); }
  return selection[0];
 }
}
