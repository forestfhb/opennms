package org.opennms.features.topology.api.support;

import com.vaadin.shared.ui.label.ContentMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Layout;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import com.vaadin.ui.Button.ClickListener;

public class DialogWindow extends Window implements ClickListener {
    private static final long serialVersionUID = -2235453349601991807L;
    final Button okayButton = new Button("OK", this);
    final UI parentWindow;
    
     public DialogWindow(final UI parentWindow, final String title, final String description) {
        this.parentWindow = parentWindow;
        setCaption(title);
        setImmediate(true);
        setResizable(false);
        setModal(true);
        setWidth(400, Unit.PIXELS);
        setContent(createContent(description));
        parentWindow.addWindow(this);
    }
     
     private Layout createMainArea(final String description) {
         HorizontalLayout layout = new HorizontalLayout();
         layout.setSpacing(true);
         layout.setMargin(true);
         layout.setWidth(100, Unit.PERCENTAGE);
         Label label = new Label(description, ContentMode.PREFORMATTED);
         label.setWidth(100, Unit.PERCENTAGE);
         layout.addComponent(label);
         return layout;
     }
     
     private Layout createContent(final String description) {
         VerticalLayout content = new VerticalLayout();
         content.setWidth(100, Unit.PERCENTAGE);
         
         Layout footer = createFooter();
         Layout mainArea = createMainArea(description);
         
         content.addComponent(mainArea);
         content.addComponent(footer);
         content.setExpandRatio(mainArea, 1);
         return content;
     }
     
     private Layout createFooter() {
         HorizontalLayout footer = new HorizontalLayout();
         footer.setSpacing(true);
         footer.setMargin(true);
         footer.setWidth(100, Unit.PERCENTAGE);
         footer.addComponent(okayButton);
         footer.setComponentAlignment(okayButton, Alignment.BOTTOM_RIGHT);
         return footer;
     }

    @Override
    public void buttonClick(Button.ClickEvent event) {
        if (event.getButton() == okayButton) parentWindow.removeWindow(DialogWindow.this);
    }

}
