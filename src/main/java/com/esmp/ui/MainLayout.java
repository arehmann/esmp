package com.esmp.ui;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;

/** AppLayout shell providing sidebar navigation for all ESMP views. */
public class MainLayout extends AppLayout {

  public MainLayout() {
    DrawerToggle toggle = new DrawerToggle();
    H1 appName = new H1("ESMP");
    appName.getStyle().set("font-size", "var(--lumo-font-size-l)").set("margin", "0");

    SideNav nav = new SideNav();
    nav.addItem(
        new SideNavItem("Dashboard", DashboardView.class, VaadinIcon.DASHBOARD.create()),
        new SideNavItem("Lexicon", LexiconView.class, VaadinIcon.BOOK.create()),
        new SideNavItem("Schedule", ScheduleView.class, VaadinIcon.CALENDAR.create()));

    Scroller scroller = new Scroller(nav);
    scroller.setClassName("drawer-scroller");
    addToDrawer(scroller);
    addToNavbar(toggle, appName);
  }
}
