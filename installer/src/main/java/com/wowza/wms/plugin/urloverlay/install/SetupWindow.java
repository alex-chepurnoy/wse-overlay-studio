/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * The window-based setup for Windows: the same steps as the command line (InstallSession), in six screens (choose the
 * install, choose applications, Chrome, review, progress, done) and a removal dialog. Started by the setup program's
 * launchers with --gui, or --gui Uninstall. Every screen is filled through a show method, so the screens can also be
 * painted with sample data for the documentation, without a WSE.
 */
final class SetupWindow
{
	static final String TITLE = "URL Overlay Setup";
	private static final Color PROBLEM = new Color(0xB00020);

	// ---- the screens, as data ---------------------------------------------------------------------------------------

	/** One install offered on the first screen. */
	record Found(Path home, String version)
	{
		@Override
		public String toString()
		{
			return "Wowza Streaming Engine " + (version == null ? "" : version.replaceAll("\\+.*", "")) + "    " + home;
		}
	}

	/** One application on the second screen. */
	record AppRow(String name, String note, boolean enabled, boolean selected)
	{
	}

	/** The Chrome screen: how Chrome is set up now, and the version a download would get (null while unknown). */
	record ChromeState(String alreadySet, String downloadVersion, String downloadProblem)
	{
	}

	enum ChromeChoice
	{
		DOWNLOAD, EXISTING, SKIP
	}

	// ---- widgets ----------------------------------------------------------------------------------------------------

	final JPanel root = new JPanel(new BorderLayout());
	private final JLabel heading = new JLabel();
	private final JLabel subheading = new JLabel();
	private final CardLayout cards = new CardLayout();
	private final JPanel body = new JPanel(cards);
	final JButton back = new JButton("Back");
	final JButton next = new JButton("Next");
	final JButton cancel = new JButton("Cancel");

	// folder
	private final DefaultListModel<Found> foundModel = new DefaultListModel<>();
	final JList<Found> foundList = new JList<>(foundModel);
	final JButton browse = new JButton("Browse...");
	private final JLabel folderStatus = new JLabel(" ");
	// applications
	private final JPanel appBoxes = new JPanel();
	final List<JCheckBox> appChecks = new ArrayList<>();
	// chrome
	final JRadioButton download = new JRadioButton();
	final JRadioButton existing = new JRadioButton("Use a Chrome already on this computer");
	final JRadioButton skip = new JRadioButton("Skip for now (web pages cannot be drawn until Chrome is set up)");
	final JTextField chromePath = new JTextField(36);
	final JTextField driverPath = new JTextField(36);
	private final JLabel downloadNote = new JLabel();
	private final JLabel chromeKept = new JLabel();
	private final JPanel chromeChoices = new JPanel();
	// review
	private final JPanel summary = new JPanel();
	private final JTextArea details = new JTextArea(10, 60);
	private final JLabel problem = new JLabel(" ");
	final JButton checkAgain = new JButton("Check again");
	final JCheckBox restartManager = new JCheckBox("Restart WSE Manager now, so it loads the URL Overlay bridge", true);
	private final JLabel restartNote = new JLabel("<html><body style='width:600px'>People signed in to WSE Manager are signed out and sign in again.</body></html>");
	// progress
	private final JLabel progressLabel = new JLabel(" ");
	final JProgressBar bar = new JProgressBar(0, 1000);
	private final JTextArea progressLog = new JTextArea(9, 60);
	// done
	private final JPanel doneBody = new JPanel();
	// removal
	final JCheckBox keepTemplates = new JCheckBox("Keep the starter overlay templates");
	final JCheckBox purge = new JCheckBox("Also delete the overlays you saved");
	private final JLabel removeHome = new JLabel();
	private final JTextArea removeDetails = new JTextArea(8, 60);
	private final JLabel removeProblem = new JLabel(" ");

	SetupWindow()
	{
		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
		head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
				BorderFactory.createEmptyBorder(14, 18, 12, 18)));
		head.setBackground(UIManager.getColor("TextField.background"));
		head.setOpaque(true);
		heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 3f));
		head.add(heading);
		head.add(Box.createVerticalStrut(4));
		head.add(subheading);
		root.add(head, BorderLayout.NORTH);
		body.setBorder(BorderFactory.createEmptyBorder(14, 18, 10, 18));
		body.add(folderCard(), "folder");
		body.add(appsCard(), "apps");
		body.add(chromeCard(), "chrome");
		body.add(reviewCard(), "review");
		body.add(progressCard(), "progress");
		body.add(doneCard(), "done");
		body.add(removeCard(), "remove");
		root.add(body, BorderLayout.CENTER);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
		buttons.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
		buttons.add(back);
		buttons.add(next);
		buttons.add(Box.createHorizontalStrut(8));
		buttons.add(cancel);
		root.add(buttons, BorderLayout.SOUTH);
		root.setPreferredSize(new Dimension(720, 500));
	}

	private static JLabel wrap(String html)
	{
		return new JLabel("<html><body style='width:600px'>" + html + "</body></html>");
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private JPanel folderCard()
	{
		JPanel p = new JPanel(new BorderLayout(0, 8));
		foundList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		foundList.setVisibleRowCount(6);
		p.add(new JLabel("Installs found on this computer:"), BorderLayout.NORTH);
		p.add(new JScrollPane(foundList), BorderLayout.CENTER);
		JPanel south = new JPanel(new BorderLayout());
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		row.add(new JLabel("Not listed? "));
		row.add(browse);
		south.add(row, BorderLayout.NORTH);
		folderStatus.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		south.add(folderStatus, BorderLayout.SOUTH);
		p.add(south, BorderLayout.SOUTH);
		return p;
	}

	private JPanel appsCard()
	{
		JPanel p = new JPanel(new BorderLayout(0, 8));
		appBoxes.setLayout(new BoxLayout(appBoxes, BoxLayout.Y_AXIS));
		p.add(new JScrollPane(appBoxes), BorderLayout.CENTER);
		p.add(wrap("You can also do this later on the URL Overlay page in Wowza Streaming Engine Manager."), BorderLayout.SOUTH);
		return p;
	}

	private JPanel chromeCard()
	{
		JPanel p = new JPanel(new BorderLayout());
		chromeChoices.setLayout(new BoxLayout(chromeChoices, BoxLayout.Y_AXIS));
		ButtonGroup g = new ButtonGroup();
		g.add(download);
		g.add(existing);
		g.add(skip);
		chromeChoices.add(left(download, 0));
		chromeChoices.add(left(downloadNote, 26));
		chromeChoices.add(Box.createVerticalStrut(12));
		chromeChoices.add(left(existing, 0));
		for(int k = 0; k < 2; k++)
		{
			JTextField f = k == 0 ? chromePath : driverPath;
			JLabel l = new JLabel(k == 0 ? "Chrome:" : "ChromeDriver:");
			l.setPreferredSize(new Dimension(90, l.getPreferredSize().height));
			JButton b = new JButton("Browse...");
			b.addActionListener(e -> {
				JFileChooser fc = new JFileChooser();
				if(fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION)
				{
					f.setText(fc.getSelectedFile().toString());
					existing.setSelected(true);
				}
			});
			JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
			row.add(l);
			row.add(f);
			row.add(b);
			chromeChoices.add(left(row, 20));
		}
		chromeChoices.add(Box.createVerticalStrut(12));
		chromeChoices.add(left(skip, 0));
		p.add(chromeKept, BorderLayout.NORTH);
		p.add(chromeChoices, BorderLayout.CENTER);
		return p;
	}

	// one left-aligned row of the Chrome screen, indented by the given pixels
	private static JComponent left(JComponent c, int indent)
	{
		JPanel row = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 0));
		row.add(c, BorderLayout.WEST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private JPanel reviewCard()
	{
		JPanel p = new JPanel(new BorderLayout(0, 8));
		summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
		p.add(summary, BorderLayout.NORTH);
		details.setEditable(false);
		details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, details.getFont().getSize() - 1));
		JPanel mid = new JPanel(new BorderLayout(0, 4));
		mid.add(new JLabel("Every change, file by file:"), BorderLayout.NORTH);
		mid.add(new JScrollPane(details), BorderLayout.CENTER);
		p.add(mid, BorderLayout.CENTER);
		JPanel south = new JPanel(new BorderLayout(8, 4));
		JPanel restart = new JPanel(new BorderLayout());
		restart.add(restartManager, BorderLayout.NORTH);
		restartNote.setBorder(BorderFactory.createEmptyBorder(0, 26, 0, 0));
		restart.add(restartNote, BorderLayout.SOUTH);
		south.add(restart, BorderLayout.NORTH);
		problem.setForeground(PROBLEM);
		south.add(problem, BorderLayout.CENTER);
		south.add(checkAgain, BorderLayout.EAST);
		p.add(south, BorderLayout.SOUTH);
		return p;
	}

	private JPanel progressCard()
	{
		JPanel p = new JPanel(new BorderLayout(0, 8));
		JPanel top = new JPanel(new BorderLayout(0, 6));
		top.add(progressLabel, BorderLayout.NORTH);
		top.add(bar, BorderLayout.CENTER);
		p.add(top, BorderLayout.NORTH);
		progressLog.setEditable(false);
		progressLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, progressLog.getFont().getSize() - 1));
		p.add(new JScrollPane(progressLog), BorderLayout.CENTER);
		return p;
	}

	private JPanel doneCard()
	{
		JPanel p = new JPanel(new BorderLayout());
		doneBody.setLayout(new BoxLayout(doneBody, BoxLayout.Y_AXIS));
		p.add(doneBody, BorderLayout.NORTH);
		return p;
	}

	private JPanel removeCard()
	{
		JPanel p = new JPanel(new BorderLayout(0, 8));
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.add(removeHome);
		top.add(Box.createVerticalStrut(8));
		top.add(keepTemplates);
		top.add(purge);
		p.add(top, BorderLayout.NORTH);
		removeDetails.setEditable(false);
		removeDetails.setFont(new Font(Font.MONOSPACED, Font.PLAIN, removeDetails.getFont().getSize() - 1));
		JPanel mid = new JPanel(new BorderLayout(0, 4));
		mid.add(new JLabel("What will be put back or deleted:"), BorderLayout.NORTH);
		mid.add(new JScrollPane(removeDetails), BorderLayout.CENTER);
		p.add(mid, BorderLayout.CENTER);
		removeProblem.setForeground(PROBLEM);
		p.add(removeProblem, BorderLayout.SOUTH);
		return p;
	}

	private void card(String name, String title, String sub)
	{
		heading.setText(title);
		subheading.setText("<html><body style='width:640px'>" + sub + "</body></html>");
		cards.show(body, name);
	}

	// ---- show methods: what each screen displays --------------------------------------------------------------------

	void showFolder(List<Found> found, Found selected, String status, boolean statusIsProblem)
	{
		card("folder", "Choose your Wowza Streaming Engine", "URL Overlay is added to a Wowza Streaming Engine that is already installed. Choose the one to add it to.");
		foundModel.clear();
		found.forEach(foundModel::addElement);
		if(selected != null)
		{
			foundList.setSelectedValue(selected, true);
		}
		folderStatus.setText(status == null ? " " : "<html>" + esc(status) + "</html>");
		folderStatus.setForeground(statusIsProblem ? PROBLEM : UIManager.getColor("Label.foreground"));
		buttons(false, "Next", true);
	}

	void showApps(List<AppRow> rows)
	{
		card("apps", "Choose applications", "Overlays appear on the live streams of the applications you choose.");
		appBoxes.removeAll();
		appChecks.clear();
		for(AppRow r: rows)
		{
			JCheckBox b = new JCheckBox(r.name() + (r.note() == null ? "" : "    (" + r.note() + ")"), r.selected());
			b.setEnabled(r.enabled());
			b.setName(r.name());
			b.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
			appBoxes.add(b);
			if(r.enabled())
			{
				appChecks.add(b);
			}
		}
		if(rows.isEmpty())
		{
			appBoxes.add(new JLabel("No applications were found. You can add URL Overlay to one later."));
		}
		appBoxes.revalidate();
		buttons(true, "Next", true);
	}

	void showChrome(ChromeState s, ChromeChoice choice)
	{
		card("chrome", "Google Chrome", "URL Overlay uses Google Chrome to draw web pages onto video.");
		boolean kept = s.alreadySet() != null;
		chromeKept.setVisible(kept);
		chromeChoices.setVisible(!kept);
		if(kept)
		{
			chromeKept.setText("<html><body style='width:600px'>Chrome is already set up for this Wowza Streaming Engine and is kept:<br><br>" + esc(s.alreadySet())
					+ "</body></html>");
		}
		download.setText("Download Chrome from Google (recommended, about 220 MB)");
		download.setEnabled(s.downloadProblem() == null);
		downloadNote.setText("<html><body style='width:520px'>" + (s.downloadProblem() != null ? "Cannot download now: " + esc(s.downloadProblem())
				: "A copy kept only for URL Overlay, with its matching driver" + (s.downloadVersion() == null ? "" : ", version " + s.downloadVersion())
						+ ".<br>A daily check installs newer versions; to turn it off, add autoUpdate=false to capture.properties.")
				+ "</body></html>");
		(choice == ChromeChoice.DOWNLOAD && s.downloadProblem() == null ? download : choice == ChromeChoice.EXISTING ? existing : skip).setSelected(true);
		buttons(true, "Next", true);
	}

	ChromeChoice chromeChoice()
	{
		return download.isSelected() ? ChromeChoice.DOWNLOAD : existing.isSelected() ? ChromeChoice.EXISTING : ChromeChoice.SKIP;
	}

	/** @param bridge true when the plan installs or updates the Manager bridge, which the Manager loads at its start */
	void showReview(List<String> lines, String changes, String blocker, boolean bridge)
	{
		restartManager.setVisible(bridge);
		restartNote.setVisible(bridge);
		card("review", "Ready to install", "Setup backs up every settings file it changes, and puts everything back if anything goes wrong.");
		summary.removeAll();
		for(String l: lines)
		{
			JLabel b = new JLabel("<html><body style='width:640px'>• " + esc(l) + "</body></html>");
			b.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
			summary.add(b);
		}
		summary.revalidate();
		details.setText(changes);
		details.setCaretPosition(0);
		problem.setText(blocker == null ? " " : "<html><body style='width:540px'>" + esc(blocker) + "</body></html>");
		checkAgain.setVisible(blocker != null);
		buttons(true, "Install", blocker == null);
	}

	void showProgress(String label, int permille, String log, boolean cancellable)
	{
		card("progress", "Installing", "This takes a few minutes while Chrome downloads.");
		progressLabel.setText(label);
		bar.setIndeterminate(permille < 0);
		bar.setValue(Math.max(0, permille));
		if(log != null)
		{
			progressLog.setText(log);
		}
		back.setEnabled(false);
		next.setEnabled(false);
		cancel.setText(cancellable ? "Cancel download" : "Cancel");
		cancel.setEnabled(cancellable);
	}

	void appendProgress(String text)
	{
		progressLog.append(text);
		progressLog.setCaretPosition(progressLog.getDocument().getLength());
	}

	/**
	 * @param steps what to do next, one per line
	 * @param log where the record of the run is, or null
	 */
	void showDone(boolean ok, String title, String sub, List<String> steps, String log)
	{
		card("done", title, sub);
		doneBody.removeAll();
		int n = 0;
		for(String s: steps)
		{
			JLabel l = new JLabel("<html><body style='width:640px'>" + (ok ? ++n + ".&nbsp; " : "") + esc(s) + "</body></html>");
			l.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
			doneBody.add(l);
		}
		if(log != null)
		{
			JLabel l = new JLabel("<html><body style='width:640px'>A record of this run is in " + esc(log) + "</body></html>");
			l.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
			doneBody.add(l);
		}
		doneBody.revalidate();
		back.setEnabled(false);
		back.setVisible(false);
		next.setText("Close");
		next.setEnabled(true);
		cancel.setVisible(false);
	}

	void showRemove(String home, String changes, String blocker)
	{
		card("remove", "Remove URL Overlay", "This takes URL Overlay out of Wowza Streaming Engine and puts your settings files back as they were.");
		removeHome.setText("<html><body style='width:640px'>Wowza Streaming Engine: " + esc(home) + "</body></html>");
		removeDetails.setText(changes);
		removeDetails.setCaretPosition(0);
		removeProblem.setText(blocker == null ? " " : "<html><body style='width:640px'>" + esc(blocker) + "</body></html>");
		back.setVisible(false);
		next.setText("Remove");
		next.setEnabled(blocker == null);
		cancel.setText("Cancel");
		cancel.setEnabled(true);
	}

	private void buttons(boolean canBack, String nextText, boolean canNext)
	{
		back.setVisible(true);
		back.setEnabled(canBack);
		next.setText(nextText);
		next.setEnabled(canNext);
		cancel.setVisible(true);
		cancel.setText("Cancel");
		cancel.setEnabled(true);
	}

	/** Lays the whole tree out at its preferred size, for painting without a window. */
	static void layout(Component c)
	{
		c.setSize(c.getPreferredSize());
		layoutTree(c);
	}

	private static void layoutTree(Component c)
	{
		if(c instanceof java.awt.Container k)
		{
			k.doLayout();
			for(Component ch: k.getComponents())
			{
				layoutTree(ch);
			}
		}
	}

	// ---- the controller: what the buttons do -----------------------------------------------------------------------

	public static void main(String[] args)
	{
		Main.Options o;
		try
		{
			o = Main.parse(args);
		}
		catch(IllegalArgumentException e)
		{
			o = new Main.Options();
		}
		try
		{
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		}
		catch(Exception e)
		{
			// the default look is fine
		}
		if(Preflight.WINDOWS && !o.elevated && needsAdmin())
		{
			int code = Elevate.relaunch(args, false);
			if(code == 0)
			{
				return;
			}
			JOptionPane.showMessageDialog(null, "Setup needs administrator rights to change Wowza Streaming Engine, and Windows did not grant them.\n"
					+ "Setup goes on, but cannot make changes until it is started as an administrator.", TITLE, JOptionPane.WARNING_MESSAGE);
		}
		boolean remove = "Uninstall".equals(o.command);
		Main.Options opts = o;
		SwingUtilities.invokeLater(() -> new Controller(new SetupWindow(), opts, remove).start());
	}

	private static boolean needsAdmin()
	{
		String pf = System.getenv("ProgramFiles");
		return pf != null && !Preflight.notWritable(List.of(Paths.get(pf))).isEmpty();
	}

	/** Drives the screens; every call into InstallSession runs off the window's thread. */
	static final class Controller
	{
		private final SetupWindow w;
		private final Main.Options o;
		private final boolean remove;
		private final JFrame frame = new JFrame(TITLE);
		private final ByteArrayOutputStream report = new ByteArrayOutputStream();
		private final PrintStream out = new PrintStream(report, true, StandardCharsets.UTF_8);
		private String screen;
		private InstallSession session;
		private List<Wse.App> chosen = List.of();
		private ChromeDownload.Build build;
		private String buildProblem;
		private InstallSession.ServerChrome serverChrome;
		private ChromeChoice choice = ChromeChoice.DOWNLOAD;
		private final AtomicBoolean cancelDownload = new AtomicBoolean();
		private volatile boolean working;

		Controller(SetupWindow w, Main.Options o, boolean remove)
		{
			this.w = w;
			this.o = o;
			this.remove = remove;
		}

		void start()
		{
			frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			frame.addWindowListener(new java.awt.event.WindowAdapter()
			{
				@Override
				public void windowClosing(java.awt.event.WindowEvent e)
				{
					quit();
				}
			});
			frame.setContentPane(w.root);
			w.back.addActionListener(e -> back());
			w.next.addActionListener(e -> next());
			w.cancel.addActionListener(e -> cancel());
			w.browse.addActionListener(e -> browse());
			w.checkAgain.addActionListener(e -> review());
			w.keepTemplates.addActionListener(e -> removePlan());
			w.purge.addActionListener(e -> removePlan());
			frame.pack();
			frame.setLocationRelativeTo(null);
			frame.setVisible(true);
			List<Found> found = new ArrayList<>();
			Path recorded = Setup.recorded();
			Found pick = null;
			for(Path h: Wse.found())
			{
				Found f = new Found(h, version(h));
				found.add(f);
				if(recorded != null && h.toAbsolutePath().normalize().equals(recorded.toAbsolutePath().normalize()))
				{
					pick = f;
				}
			}
			if(o.home != null)
			{
				Path h = Paths.get(o.home);
				pick = new Found(h, version(h));
				found.removeIf(f -> f.home().equals(h));
				found.add(0, pick);
			}
			if(pick == null && found.size() == 1)
			{
				pick = found.get(0);
			}
			if(remove && pick != null)
			{
				openSession(pick.home());
				return;
			}
			screen = "folder";
			w.showFolder(found, pick, found.isEmpty() ? "No Wowza Streaming Engine was found in the usual places. Use Browse to choose its folder." : null, false);
			w.next.setEnabled(pick != null || !found.isEmpty());
		}

		private static String version(Path h)
		{
			try
			{
				return Wse.version(h);
			}
			catch(Exception e)
			{
				return null;
			}
		}

		private void browse()
		{
			JFileChooser fc = new JFileChooser();
			fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			fc.setDialogTitle("Choose the Wowza Streaming Engine folder");
			if(fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION)
			{
				return;
			}
			Path h = fc.getSelectedFile().toPath();
			if(!Files.isRegularFile(h.resolve("conf").resolve("Server.xml")))
			{
				w.showFolder(listed(), w.foundList.getSelectedValue(), h + " is not a Wowza Streaming Engine folder: it has no conf\\Server.xml.", true);
				return;
			}
			Found f = new Found(h, version(h));
			List<Found> all = listed();
			all.add(f);
			w.showFolder(all, f, null, false);
		}

		private List<Found> listed()
		{
			List<Found> l = new ArrayList<>();
			for(int k = 0; k < w.foundList.getModel().getSize(); k++)
			{
				l.add(w.foundList.getModel().getElementAt(k));
			}
			return l;
		}

		private void back()
		{
			switch(screen)
			{
				case "apps" -> {
					screen = "folder";
					w.showFolder(listed(), w.foundList.getSelectedValue(), null, false);
				}
				case "chrome" -> showApps();
				case "review" -> showChrome();
				default -> {
					// nothing before the first screen
				}
			}
		}

		private void next()
		{
			switch(screen)
			{
				case "folder" -> {
					Found f = w.foundList.getSelectedValue();
					if(f == null)
					{
						w.showFolder(listed(), null, "Choose an install first.", true);
						return;
					}
					openSession(f.home());
				}
				case "apps" -> {
					List<String> names = new ArrayList<>();
					w.appChecks.stream().filter(JCheckBox::isSelected).forEach(b -> names.add(b.getName()));
					chosen = session.offerable().stream().filter(a -> names.contains(label(a))).toList();
					showChrome();
				}
				case "chrome" -> {
					choice = w.chromeChoice();
					if(choice == ChromeChoice.EXISTING && (w.chromePath.getText().isBlank() || w.driverPath.getText().isBlank()))
					{
						JOptionPane.showMessageDialog(frame, "Give both Chrome and ChromeDriver, or choose another option.", TITLE, JOptionPane.INFORMATION_MESSAGE);
						return;
					}
					review();
				}
				case "review" -> install();
				case "remove" -> uninstall();
				default -> quit();
			}
		}

		private void cancel()
		{
			if(working && "progress".equals(screen))
			{
				cancelDownload.set(true);
				w.cancel.setEnabled(false);
				return;
			}
			quit();
		}

		private void quit()
		{
			if(working)
			{
				if(!cancelDownload.get() && "progress".equals(screen) && w.cancel.isEnabled())
				{
					cancelDownload.set(true);
				}
				return;
			}
			frame.dispose();
		}

		/** Runs work off the window's thread, then done on it; a failure is shown on the done screen. */
		private <T> void background(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> done)
		{
			working = true;
			w.next.setEnabled(false);
			w.back.setEnabled(false);
			new SwingWorker<T, Void>()
			{
				@Override
				protected T doInBackground() throws Exception
				{
					return work.call();
				}

				@Override
				protected void done()
				{
					working = false;
					try
					{
						done.accept(get());
					}
					catch(Exception e)
					{
						Throwable t = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
						failed("Setup stopped because of an unexpected problem: " + Plain.say(t) + ".", false);
					}
				}
			}.execute();
		}

		private void openSession(Path home)
		{
			background(() -> InstallSession.open(home, o.wsemHome == null ? null : Paths.get(o.wsemHome), out), s -> {
				session = s;
				out.println("URL Overlay " + s.payload.version() + ", WSE " + (s.wseVersion == null ? "version unknown" : s.wseVersion) + " at " + home);
				if(!s.supported())
				{
					screen = "folder";
					w.showFolder(listed(), w.foundList.getSelectedValue(), "This Wowza Streaming Engine is version " + s.wseVersion
							+ ". URL Overlay needs version 4.11.1 or later.", true);
					return;
				}
				if(remove)
				{
					screen = "remove";
					removePlan();
				}
				else
				{
					showApps();
				}
			});
		}

		private static String label(Wse.App a)
		{
			return a.vhost().equals("_defaultVHost_") ? a.name() : a.vhost() + "/" + a.name();
		}

		private void showApps()
		{
			screen = "apps";
			w.showApps(appRows(session.apps, chosen));
		}

		/** The application list: live ones without the module can be chosen, the rest are named with why not. */
		static List<AppRow> appRows(List<Wse.App> apps, List<Wse.App> chosen)
		{
			List<AppRow> rows = new ArrayList<>();
			for(Wse.App a: apps)
			{
				String name = label(a);
				if(a.kind().equals("vod"))
				{
					rows.add(new AppRow(name, "plays recorded video, so it cannot show overlays", false, false));
				}
				else if(a.installed())
				{
					rows.add(new AppRow(name, "URL Overlay is already on", false, true));
				}
				else
				{
					boolean off = a.transcoder() == null || !a.transcoder().contains("transcoder");
					String note = off ? "its transcoder is off; overlays show once it is on" : a.others().contains("VIF") ? "also runs Video Intelligence; a stream uses one or the other" : null;
					rows.add(new AppRow(name, note, true, chosen.contains(a)));
				}
			}
			return rows;
		}

		private void showChrome()
		{
			screen = "chrome";
			background(() -> {
				serverChrome = session.serverChrome();
				if(!serverChrome.set() && build == null && buildProblem == null)
				{
					String platform = ChromeDownload.platform();
					try
					{
						build = platform == null ? null : ChromeDownload.stable(ChromeDownload.client(), platform);
						buildProblem = platform == null ? "there is no Chrome for Testing for this computer" : null;
					}
					catch(Exception e)
					{
						buildProblem = "this computer cannot reach Google's download site (" + Plain.say(e) + ")";
					}
				}
				return serverChrome;
			}, sc -> {
				String kept = sc.set() ? sc.driver() + (sc.chrome() == null || sc.chrome().isEmpty() ? "" : "\n" + sc.chrome()) : null;
				w.showChrome(new ChromeState(kept, build == null ? null : build.version(), buildProblem), choice);
			});
		}

		/** The Chrome the capture service gets, from the Chrome screen: chrome and chromedriver, either may be null. */
		private Path[] chromePaths()
		{
			if(serverChrome != null && serverChrome.set())
			{
				return new Path[] { serverChrome.chrome() == null || serverChrome.chrome().isBlank() ? null : Paths.get(serverChrome.chrome()), Paths.get(serverChrome.driver()) };
			}
			return switch(choice)
			{
				case EXISTING -> new Path[] { Paths.get(w.chromePath.getText().trim()), Paths.get(w.driverPath.getText().trim()) };
				case DOWNLOAD -> {
					if(build == null)
					{
						yield new Path[] { null, null };
					}
					Path dir = session.capture.chromeRoot().resolve(build.version());
					yield new Path[] { ChromeDownload.chromeIn(dir, build.platform()), ChromeDownload.driverIn(dir, build.platform()) };
				}
				default -> new Path[] { null, null };
			};
		}

		private boolean downloading()
		{
			return (serverChrome == null || !serverChrome.set()) && choice == ChromeChoice.DOWNLOAD && build != null;
		}

		private void review()
		{
			screen = "review";
			Map<String, String> props = session.properties();
			Path[] chrome = chromePaths();
			boolean dl = downloading();
			background(() -> {
				InstallSession.Problem p = session.installBlocker(chosen, dl);
				List<Installer.Op> ops = session.planQuietly(chosen, props);
				boolean capture = session.captureNeeded(session.captureConfig(chrome[0], chrome[1]));
				return new Object[] { p, describe(ops, session.home, List.of()), session.touchesBridge(ops), capture };
			}, r -> {
				InstallSession.Problem p = (InstallSession.Problem)r[0];
				List<String> lines = summary(session, chosen, dl ? build.version() : null, choice, serverChrome);
				if((Boolean)r[3])
				{
					lines.add(captureLine());
				}
				w.showReview(lines, (String)r[1], p == null ? null : friendly(p), (Boolean)r[2]);
			});
		}

		/**
		 * A plan in the window's words: paths relative to the chosen install (named above the list), and the library jars
		 * gathered under one line per action, listed below it. The log keeps the full paths.
		 * @param downloads downloaded Chrome folders the run deletes
		 */
		static String describe(List<Installer.Op> ops, Path home, List<Path> downloads)
		{
			if(ops.isEmpty() && downloads.isEmpty())
			{
				return "Nothing to change.";
			}
			StringBuilder b = new StringBuilder();
			Map<String, List<String>> jars = new java.util.LinkedHashMap<>();
			Path lib = home.resolve("lib");
			for(Installer.Op o: ops)
			{
				String name = o.path.getFileName().toString();
				if(lib.equals(o.path.getParent()) && name.endsWith(".jar"))
				{
					jars.computeIfAbsent(o.kind, k -> new ArrayList<>()).add(name);
					continue;
				}
				b.append(verb(o.kind)).append("  ").append(relative(home, o.path));
				b.append('\n');
				if(o.changes != null)
				{
					for(com.wowza.wms.plugin.urloverlay.config.ConfigEdits.Change c: o.changes)
					{
						if(c.inserted() != null)
						{
							for(String l: c.inserted().split("\r?\n"))
							{
								if(!l.isBlank())
								{
									b.append("      + ").append(l.strip()).append('\n');
								}
							}
						}
						else
						{
							b.append("      ").append(c.path()).append(": ").append(c.before()).append(" -> ").append(c.after()).append('\n');
						}
					}
				}
			}
			for(Map.Entry<String, List<String>> j: jars.entrySet())
			{
				int n = j.getValue().size();
				b.append(verb(j.getKey())).append("  ").append(n).append(n == 1 ? " library jar in " : " library jars in ").append(relative(home, lib))
						.append(java.io.File.separator).append('\n');
				for(String name: j.getValue())
				{
					b.append("      ").append(name).append('\n');
				}
			}
			for(Path d: downloads)
			{
				b.append("delete  ").append(relative(home, d)).append("  (the Chrome Setup downloaded)\n");
			}
			return b.toString();
		}

		private static String verb(String kind)
		{
			return switch(kind)
			{
				case "edit" -> "change";
				case "restore" -> "put back";
				case "retire" -> "set aside";
				default -> kind;
			};
		}

		private static String relative(Path home, Path p)
		{
			Path h = home.toAbsolutePath().normalize(), a = p.toAbsolutePath().normalize();
			return a.startsWith(h) ? h.relativize(a).toString() : a.toString();
		}

		/** A progress log line as the window shows it: no install folder in front of paths, a download named by its file. */
		static String shorten(String line, Path home)
		{
			String t = line.replace(home.toAbsolutePath().normalize() + java.io.File.separator, "");
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://\\S*/([^/\\s]+)").matcher(t);
			return m.replaceAll("$1");
		}

		/**
		 * What is left to do after an install, and only that. WSE is stopped for the install, so it is started unless it
		 * runs again already; the Manager needs a restart only when the bridge changed and Setup did not restart it.
		 * @param restarted whether Setup restarted the Manager: "yes", "no", or why it could not
		 */
		static List<String> doneSteps(String restarted, boolean bridge, boolean wseRunning)
		{
			List<String> l = new ArrayList<>();
			if(!wseRunning)
			{
				l.add("Start the Wowza Streaming Engine service (in Windows Services).");
			}
			if(bridge && !"yes".equals(restarted))
			{
				l.add("Restart the Wowza Streaming Engine Manager service (in Windows Services), so it loads the URL Overlay bridge."
						+ ("no".equals(restarted) ? "" : " Setup could not restart it: " + restarted + "."));
			}
			l.add("Open Wowza Streaming Engine Manager and choose URL Overlay at the top of the page.");
			return l;
		}

		/** The review screen's plain summary. */
		static List<String> summary(InstallSession s, List<Wse.App> chosen, String downloadVersion, ChromeChoice choice, InstallSession.ServerChrome sc)
		{
			List<String> l = new ArrayList<>();
			l.add("Add URL Overlay to Wowza Streaming Engine " + (s.wseVersion == null ? "" : s.wseVersion.replaceAll("\\+.*", "") + " ") + "in " + s.home);
			l.addAll(summaryRest(chosen.stream().map(Controller::label).toList(), downloadVersion, choice, sc != null && sc.set()));
			l.add(bridgeLine(s.bridgeGate.ok()));
			return l;
		}

		static String captureLine()
		{
			return "Install the capture service, which runs Chrome under its own limited account, apart from Wowza Streaming Engine";
		}

		static String bridgeLine(boolean ok)
		{
			return ok ? "Add the URL Overlay bridge to WSE Manager, so the page reaches WSE from any browser that can open the Manager"
					: "Leave out the URL Overlay bridge: this WSE Manager is not one it was built for, so the page works only in a browser on the server itself";
		}

		static List<String> summaryRest(List<String> apps, String downloadVersion, ChromeChoice choice, boolean chromeKept)
		{
			List<String> l = new ArrayList<>();
			l.add(apps.isEmpty() ? "No application is changed now; turn URL Overlay on for one later in Wowza Streaming Engine Manager"
					: "Turn URL Overlay on for: " + String.join(", ", apps));
			l.add(chromeKept ? "Keep the Chrome this server already uses"
					: downloadVersion != null ? "Download Chrome for Testing " + downloadVersion + " from Google (about 220 MB)"
					: choice == ChromeChoice.EXISTING ? "Use the Chrome you chose" : "Leave Chrome to be set up later");
			l.add("Add the URL Overlay page to Wowza Streaming Engine Manager");
			return l;
		}

		/** A blocker in the window's words: the window has no command line to point at. */
		static String friendly(InstallSession.Problem p)
		{
			String m = p.message();
			if(m.startsWith("Cannot write to"))
			{
				return "Setup cannot change the files of this Wowza Streaming Engine. Close Setup and start it again as an administrator.";
			}
			if(m.contains("is in use") || m.contains("is running"))
			{
				return "Wowza Streaming Engine is running. Stop the Wowza Streaming Engine service (in Windows Services), then click Check again.";
			}
			return m;
		}

		private void install()
		{
			screen = "progress";
			cancelDownload.set(false);
			boolean dl = downloading();
			boolean restart = w.restartManager.isVisible() && w.restartManager.isSelected();
			Map<String, String> props = session.properties();
			Path[] chosenChrome = chromePaths();
			Plain.changed = false;
			Plain.detail = new PrintStream(report, true, StandardCharsets.UTF_8);
			w.showProgress(dl ? "Downloading Chrome..." : "Applying changes...", dl ? 0 : -1, "", dl);
			PrintStream live = new PrintStream(new Main.Tee(report, new OutputStream()
			{
				private final ByteArrayOutputStream line = new ByteArrayOutputStream();

				@Override
				public void write(int b)
				{
					line.write(b);
					if(b == '\n')
					{
						String s = line.toString(StandardCharsets.UTF_8);
						line.reset();
						SwingUtilities.invokeLater(() -> w.appendProgress(shorten(s, session.home)));
					}
				}
			}), true, StandardCharsets.UTF_8);
			working = true;
			new SwingWorker<String[], Object[]>()
			{
				@Override
				protected String[] doInBackground() throws Exception
				{
					InstallSession s = InstallSession.open(session.home, o.wsemHome == null ? null : Paths.get(o.wsemHome), live);
					InstallSession.Problem p = s.installBlocker(chosen, dl);
					if(p != null)
					{
						return new String[] { "blocked", friendly(p) };
					}
					ChromeDownload.Pair pair = null;
					boolean fetched = false;
					if(dl)
					{
						try
						{
							ChromeFolders.Result r = ChromeFolders.ensure(s.capture.chromeRoot(), build, s.configuredChrome(), s.sys, ChromeDownload.client(), live,
									(file, name, done, total) -> publish(new Object[] { file, name, done, total }), cancelDownload::get, () -> {
										Plain.changed = true;
										s.stopCaptureForChrome();
										live.println("Downloading Chrome for Testing " + build.version() + " and its chromedriver (about 220 MB)...");
									});
							pair = r.pair();
							fetched = r.downloaded();
						}
						catch(java.io.InterruptedIOException e)
						{
							s.startCaptureIfStopped();
							return new String[] { "cancelled" };
						}
						catch(java.io.IOException | RuntimeException e)
						{
							s.startCaptureIfStopped();
							throw e;
						}
					}
					SwingUtilities.invokeLater(() -> w.showProgress("Setting up the capture service and applying changes...", -1, null, false));
					List<Installer.Op> ops = s.planInstall(chosen, props);
					Installer.print(live, ops, false);
					CaptureSetup.Config cfg = s.captureConfig(pair != null ? pair.chrome() : chosenChrome[0], pair != null ? pair.driver() : chosenChrome[1]);
					try
					{
						InstallSession.Installed done = s.applyInstall(ops, chosen, cfg);
						live.println("Installed.");
						done.backups().forEach(b -> live.println("  backup: " + b));
						live.println("Capture service: " + (done.serviceProblem() == null ? "running." : done.serviceProblem()));
						Main.ownershipReport(s, live);
						boolean bridge = s.touchesBridge(ops);
						String restarted = "no";
						if(bridge && restart)
						{
							SwingUtilities.invokeLater(() -> w.showProgress("Restarting WSE Manager...", -1, null, false));
							String why = ManagerBridge.restart(s.managerHome);
							live.println(why == null ? "WSE Manager was restarted." : "WSE Manager could not be restarted: " + why);
							restarted = why == null ? "yes" : why;
						}
						boolean wseRunning = Preflight.wseRunning(s.home);
						Main.nextSteps(live, true, chosen.isEmpty(), bridge && !"yes".equals(restarted) ? ManagerBridge.restartCommand(s.managerHome) : null, wseRunning);
						return new String[] { "ok", log(s.home), restarted, String.valueOf(bridge), done.serviceProblem() == null ? "" : done.serviceProblem(),
								String.valueOf(wseRunning) };
					}
					catch(Installer.Failed f)
					{
						live.println("Failed: " + f.getMessage());
						s.startCaptureIfStopped();
						boolean cleaned = true;
						if(fetched)
						{
							try
							{
								ChromeDownload.deleteTree(pair.dir());
							}
							catch(java.io.IOException e)
							{
								cleaned = false;
								live.println("The downloaded Chrome could not be removed: " + Plain.say(e) + ". The next run cleans it up.");
							}
						}
						return new String[] { f.rolledBack && cleaned ? "rolledback" : "partial", log(s.home), f.getMessage() };
					}
				}

				@Override
				protected void process(List<Object[]> chunks)
				{
					Object[] c = chunks.get(chunks.size() - 1);
					long done = (Long)c[2], total = (Long)c[3];
					String mb = total > 0 ? (done >> 20) + " of " + (total >> 20) + " MB" : (done >> 20) + " MB";
					w.showProgress("Downloading " + c[1] + " (" + c[0] + " of 2): " + mb, total > 0 ? (int)(done * 1000 / total) : -1, null, true);
				}

				@Override
				protected void done()
				{
					working = false;
					String[] r;
					try
					{
						r = get();
					}
					catch(Exception e)
					{
						Throwable t = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
						failed("Setup stopped because of an unexpected problem: " + Plain.say(t) + (Plain.changed ? ". Some changes may remain; the record of this run lists them." : ". Nothing was changed."), true);
						return;
					}
					switch(r[0])
					{
						case "blocked" -> {
							screen = "review";
							review();
						}
						case "cancelled" -> {
							screen = "review";
							review();
							JOptionPane.showMessageDialog(frame, "The download was cancelled; URL Overlay was not installed.", TITLE, JOptionPane.INFORMATION_MESSAGE);
						}
						case "ok" -> {
							screen = "done";
							List<String> steps = new ArrayList<>(doneSteps(r[2], Boolean.parseBoolean(r[3]), Boolean.parseBoolean(r[5])));
							steps.add(r[4].isEmpty() ? "The capture service, which draws the web pages, is running." : "Start the capture service: " + r[4] + ".");
							w.showDone(true, "URL Overlay is installed", steps.size() > 2 || !r[4].isEmpty() ? "A few more steps and it is ready to use." : "It is ready to use.", steps, r[1]);
						}
						default -> {
							screen = "done";
							boolean back = r[0].equals("rolledback");
							w.showDone(false, "Setup could not finish", back ? "Nothing was changed: everything Setup did was put back."
									: "Some changes could not be put back. The record below lists what this run did.", back ? List.of("What went wrong: " + r[2])
											: List.of("What went wrong: " + r[2], "To fix it: run the setup again. It repairs what this run left, the capture service included."), r[1]);
						}
					}
				}
			}.execute();
		}

		private String log(Path home)
		{
			try
			{
				return Main.writeLog(home, report.toByteArray()).toString();
			}
			catch(Exception e)
			{
				return null;
			}
		}

		private void failed(String message, boolean wroteLog)
		{
			screen = "done";
			w.showDone(false, "Setup could not finish", message, List.of(), session != null && wroteLog ? log(session.home) : null);
		}

		// ---- removal ------------------------------------------------------------------------------------------------

		private void removePlan()
		{
			if(session == null || working)
			{
				return;
			}
			boolean keep = w.keepTemplates.isSelected(), all = w.purge.isSelected();
			background(() -> {
				if(!Files.isRegularFile(Installer.manifestFile(session.home)))
				{
					return new Object[] { "URL Overlay was not installed here by Setup, so there is nothing to remove.", null, true };
				}
				PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
				List<Installer.Op> ops = new Installer(session.home, session.payload, quiet).planUninstall(keep, all);
				InstallSession.Problem p = session.changeBlocker(ops, "removal");
				return new Object[] { describe(ops, session.home, session.downloads()), p == null ? null : friendly(p), false };
			}, r -> {
				w.showRemove(session.home.toString(), (String)r[0], (String)r[1]);
				if((Boolean)r[2])
				{
					w.next.setEnabled(false);
				}
			});
		}

		private void uninstall()
		{
			boolean keep = w.keepTemplates.isSelected(), all = w.purge.isSelected();
			background(() -> {
				InstallSession s = InstallSession.open(session.home, o.wsemHome == null ? null : Paths.get(o.wsemHome), out);
				List<Installer.Op> ops = s.planUninstall(keep, all);
				InstallSession.Problem p = s.changeBlocker(ops, "removal");
				if(p != null)
				{
					return new String[] { "blocked", friendly(p) };
				}
				Installer.print(out, ops, false);
				try
				{
					InstallSession.Uninstalled u = s.applyUninstall(ops);
					List<String> notes = new ArrayList<>();
					u.notDeleted().forEach(n -> notes.add("Could not delete " + n + "; delete it by hand."));
					u.keptOverlays().forEach(k -> notes.add("Your saved overlays are kept in " + k + "."));
					out.println("Uninstalled.");
					notes.forEach(out::println);
					return new String[] { "ok", log(s.home), String.join("\n", notes) };
				}
				catch(InstallSession.Blocked b)
				{
					return new String[] { "blocked", friendly(b.problem) };
				}
				catch(Installer.Failed f)
				{
					out.println("Failed: " + f.getMessage());
					return new String[] { f.rolledBack ? "rolledback" : "partial", log(s.home), f.getMessage() };
				}
			}, r -> {
				switch(r[0])
				{
					case "blocked" -> {
						w.showRemove(session.home.toString(), "", r[1]);
						removePlan();
					}
					case "ok" -> {
						screen = "done";
						List<String> steps = new ArrayList<>(List.of("Restart the Wowza Streaming Engine service and the Wowza Streaming Engine Manager service (in Windows Services)."));
						if(!r[2].isEmpty())
						{
							steps.addAll(List.of(r[2].split("\n")));
						}
						w.showDone(true, "URL Overlay was removed", "Your settings files are back as they were before URL Overlay.", steps, r[1]);
					}
					default -> {
						screen = "done";
						w.showDone(false, "URL Overlay could not be removed", r[0].equals("rolledback") ? "Nothing was changed: everything was put back."
								: "Some changes could not be put back. The record below lists the backups to restore them from.", List.of("What went wrong: " + r[2]), r[1]);
					}
				}
			});
		}
	}

	/** For painting a screen without a window (documentation): the root panel, laid out. */
	JComponent paintable()
	{
		root.setSize(root.getPreferredSize());
		layout(root);
		return root;
	}
}
