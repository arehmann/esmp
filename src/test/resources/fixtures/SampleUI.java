package com.example.sample;

import com.vaadin.annotations.Theme;
import com.vaadin.navigator.Navigator;
import com.vaadin.server.VaadinRequest;
import com.vaadin.spring.annotation.SpringUI;
import com.vaadin.spring.navigator.SpringViewProvider;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Vaadin 7 UI entry point for the sample application.
 *
 * <p>This UI demonstrates: - Extending {@link com.vaadin.ui.UI} as the Vaadin application entry
 * point - {@code @SpringUI} annotation for Spring-managed UI lifecycle - Setting up a {@link
 * Navigator} with Spring-managed view providers - Initialisation of the main layout hierarchy
 */
@Theme("valo")
@SpringUI
public class SampleUI extends UI {

  @Autowired private SpringViewProvider viewProvider;

  @Override
  protected void init(VaadinRequest request) {
    VerticalLayout mainLayout = new VerticalLayout();
    mainLayout.setSizeFull();

    setContent(mainLayout);

    Navigator navigator = new Navigator(this, mainLayout);
    navigator.addProvider(viewProvider);
    setNavigator(navigator);

    if (navigator.getState().isEmpty()) {
      navigator.navigateTo("sample");
    }
  }
}
