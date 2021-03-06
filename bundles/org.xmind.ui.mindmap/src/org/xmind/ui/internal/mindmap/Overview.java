package org.xmind.ui.internal.mindmap;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import org.eclipse.draw2d.Cursors;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.LayoutListener;
import org.eclipse.draw2d.RangeModel;
import org.eclipse.draw2d.RectangleFigure;
import org.eclipse.draw2d.Viewport;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Insets;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.xmind.gef.GEF;
import org.xmind.gef.IGraphicalViewer;
import org.xmind.gef.IInputChangedListener;
import org.xmind.gef.IViewer;
import org.xmind.gef.IZoomListener;
import org.xmind.gef.ZoomManager;
import org.xmind.gef.ZoomObject;
import org.xmind.gef.draw2d.geometry.PrecisionDimension;
import org.xmind.gef.draw2d.geometry.PrecisionPoint;
import org.xmind.gef.draw2d.graphics.ScaledGraphics;
import org.xmind.ui.resources.ColorUtils;

public class Overview implements ISelectionChangedListener,
        IInputChangedListener, PropertyChangeListener, IZoomListener, Listener {

    private class ContentsFigure extends Figure {

        public ContentsFigure() {
            setOpaque(true);
        }

        @Override
        protected void paintFigure(Graphics graphics) {
            super.paintFigure(graphics);
            if (sourceContents == null || zoomScale <= 0)
                return;

            graphics.setAntialias(SWT.ON);

            graphics.pushState();
            try {
                Point offset = getBounds().getLocation();
                graphics.translate(offset);

                Graphics g = graphics;
                ScaledGraphics sg = null;
                if (ScaledGraphics.SCALED_GRAPHICS_ENABLED) {
                    sg = new ScaledGraphics(graphics);
                    sg.scale(zoomScale);
                    g = sg;
                } else {
                    g.scale(zoomScale);
                }
                try {
                    paintDelegate(g, sourceContents);
                } finally {
                    if (sg != null) {
                        sg.dispose();
                    }
                }
            } finally {
                graphics.popState();
            }

        }

        private void paintDelegate(Graphics graphics, IFigure figure) {
            Point loc = figure.getBounds().getLocation();
            graphics.translate(-loc.x, -loc.y);
            try {
                figure.paint(graphics);
            } finally {
                graphics.translate(loc.x, loc.y);
            }
        }

    }

    private class ContentsLayoutListener extends LayoutListener.Stub {

        @Override
        public void postLayout(IFigure container) {
            update();
        }
    }

    private IGraphicalViewer sourceViewer;

    private FigureCanvas sourceCanvas;

    private RangeModel sourceHorizontalRangeModel;

    private RangeModel sourceVerticalRangeModel;

    private ZoomManager sourceZoomManager;

    private IFigure sourceContents;

    private FigureCanvas canvas;

    private IFigure contents;

    private IFigure feedback;

    private boolean updating = false;

    private ContentsLayoutListener contentsListener;

    private Point moveStart = null;

    private Point sourceStart = null;

    private double zoomScale = 1.0d;

    private ResourceManager resources;

    public Overview(Composite parent, FigureCanvas sourceCanvas,
            IGraphicalViewer viewer) {
        this.sourceViewer = viewer;
        this.sourceCanvas = sourceCanvas;
        createContents(parent);
    }

    protected Control createContents(Composite parent) {
        Composite composite = new Composite(parent, SWT.BORDER);

        resources = new LocalResourceManager(JFaceResources.getResources(),
                composite);

        canvas = new FigureCanvas(composite);
        canvas.setSize(MindMapViewer.OVERVIEW_WIDTH,
                MindMapViewer.OVERVIEW_HEIGHT);
        canvas.addListener(SWT.Resize, this);
        canvas.addListener(SWT.MouseDown, this);
        canvas.addListener(SWT.MouseMove, this);
        canvas.addListener(SWT.MouseUp, this);
        canvas.addListener(SWT.MouseWheel, this);
        canvas.setBackground(
                (Color) resources.get(ColorUtils.toDescriptor("#f9fcfe"))); //$NON-NLS-1$
        contents = new ContentsFigure();
        contents.setCursor(Cursors.HAND);
        canvas.setContents(contents);
        feedback = createFeedback();
        contents.add(feedback);

        sourceZoomManager = sourceViewer.getZoomManager();

        sourceViewer.addInputChangedListener(this);
        sourceViewer.addSelectionChangedListener(this);
        sourceZoomManager.addZoomListener(this);
        hookViewport();
        hookContents();
        update();

        return composite;
    }

    public void dispose() {
        unhookContents();
        unhookViewport();
        sourceZoomManager.removeZoomListener(this);
        sourceViewer.removeSelectionChangedListener(this);
    }

    private void moveStarted(int x, int y) {
        moveStart = new Point(x, y);
        sourceStart = new Point(sourceViewer.getScrollPosition());
    }

    private void moveEnded(int x, int y) {
        if (moveStart != null) {
            if (moveStart.x == x && moveStart.y == y) {
                directMove(x, y);
            }
        }
        moveStart = null;
        sourceStart = null;
    }

    private void directMove(int x, int y) {
        Point start = feedback.getBounds().getCenter();
        Dimension offset = new PrecisionDimension(x - start.x, y - start.y)
                .scale(sourceZoomManager.getScale() / zoomScale)
                .toDraw2DDimension();
        sourceViewer.scrollDelta(offset);
    }

    private void feedbackMoved(int x, int y) {
        int dx = x - moveStart.x;
        int dy = y - moveStart.y;
        Dimension offset = new PrecisionDimension(dx, dy)
                .scale(sourceZoomManager.getScale() / zoomScale)
                .toDraw2DDimension();
        sourceViewer.scrollTo(sourceStart.getTranslated(offset));
    }

    private void changeZoom(int value) {
        if (value > 0) {
            sourceZoomManager.zoomIn();
        } else if (value < 0) {
            sourceZoomManager.zoomOut();
        }
    }

    private void update() {
        if (updating)
            return;
        updating = true;
        Display.getCurrent().asyncExec(new Runnable() {
            public void run() {
                doUpdate();
                updating = false;
            }
        });
    }

    private void doUpdate() {
        Insets margins;
        Rectangle feedbackBounds;
        Rectangle sourceBounds = sourceContents.getBounds();
        Dimension source = sourceBounds.getSize();
        Rectangle area = contents.getParent().getClientArea();
        if (area.width == 0 || area.height == 0 || source.width == 0
                || source.height == 0) {
            zoomScale = -1;
            margins = IFigure.NO_INSETS;
            feedbackBounds = null;
        } else {
            double wScale = source.width * 1.0d / area.width;
            double hScale = source.height * 1.0d / area.height;
            if (wScale > hScale) {
                zoomScale = 1 / wScale;
                int m = (int) ((area.height - source.height / wScale) / 2);
                margins = new Insets(m, 0, m, 0);
            } else {
                zoomScale = 1 / hScale;
                int m = (int) ((area.width - source.width / hScale) / 2);
                margins = new Insets(0, m, 0, m);
            }
            Viewport sourceViewport = sourceViewer.getCanvas().getViewport();
            PrecisionPoint loc = new PrecisionPoint(
                    sourceViewport.getViewLocation());
            Dimension size = sourceViewport.getSize();
            double sourceScale = sourceZoomManager.getScale();
            feedbackBounds = new Rectangle(
                    loc.scale(1 / sourceScale)
                            .translate(new PrecisionPoint(
                                    sourceBounds.getLocation()).negate())
                            .scale(zoomScale)
                            .translate(margins.left, margins.top)
                            .toDraw2DPoint(),
                    size.scale(zoomScale / sourceScale));
        }
        contents.setBounds(area.getShrinked(margins));
        contents.repaint();
        if (feedbackBounds == null) {
            feedback.setBounds(new Rectangle(1, 1, 0, 0));
            feedback.setVisible(false);
        } else {
            feedback.setBounds(feedbackBounds);
            feedback.setVisible(true);
        }
    }

    private void hookViewport() {
        Viewport sourceViewport = sourceCanvas.getViewport();
        sourceHorizontalRangeModel = sourceViewport.getHorizontalRangeModel();
        sourceHorizontalRangeModel.addPropertyChangeListener(this);
        sourceVerticalRangeModel = sourceViewport.getVerticalRangeModel();
        sourceVerticalRangeModel.addPropertyChangeListener(this);
    }

    private void unhookViewport() {
        if (sourceHorizontalRangeModel != null) {
            sourceHorizontalRangeModel.removePropertyChangeListener(this);
            sourceHorizontalRangeModel = null;
        }
        if (sourceVerticalRangeModel != null) {
            sourceVerticalRangeModel.removePropertyChangeListener(this);
            sourceVerticalRangeModel = null;
        }
    }

    private void hookContents() {
        if (contentsListener == null)
            contentsListener = new ContentsLayoutListener();
        sourceContents = sourceViewer.getLayer(GEF.LAYER_CONTENTS);
        sourceContents.addLayoutListener(contentsListener);
    }

    private void unhookContents() {
        if (contentsListener != null) {
            if (sourceContents != null) {
                sourceContents.removeLayoutListener(contentsListener);
            }
        }
    }

    private IFigure createFeedback() {
        RectangleFigure figure = new RectangleFigure();
        figure.setForegroundColor(
                (Color) resources.get(ColorUtils.toDescriptor("#44c0ff"))); //$NON-NLS-1$
        figure.setLineWidth(1);
        figure.setFill(false);
        figure.setOutline(true);
        return figure;
    }

    @Override
    public void handleEvent(Event event) {
        if (event.type == SWT.MouseDown) {
            moveStarted(event.x, event.y);
        } else if (event.type == SWT.MouseMove) {
            if (moveStart != null) {
                feedbackMoved(event.x, event.y);
            }
        } else if (event.type == SWT.MouseUp) {
            moveEnded(event.x, event.y);
        } else if (event.type == SWT.MouseWheel) {
            changeZoom(event.count);
        } else if (event.type == SWT.Resize) {
            update();
        }
    }

    @Override
    public void scaleChanged(ZoomObject source, double oldValue,
            double newValue) {
        update();
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        update();
    }

    @Override
    public void selectionChanged(SelectionChangedEvent event) {
        update();
    }

    @Override
    public void inputChanged(IViewer viewer, Object newInput, Object oldInput) {
        unhookContents();
        unhookViewport();
        hookViewport();
        hookContents();
        update();
    }

}
