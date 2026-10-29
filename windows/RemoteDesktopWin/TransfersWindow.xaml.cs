using System.Windows;

namespace RemoteDesktopWin;

/// <summary>Every file sent or received this session, with any device; each can be cancelled.</summary>
public partial class TransfersWindow : Window
{
    private readonly TransferList _list;

    public TransfersWindow(TransferList list)
    {
        InitializeComponent();
        _list = list;
        TransferListBox.ItemsSource = list.Rows;
        list.Changed += UpdateClearButton;
        Closed += (_, _) => list.Changed -= UpdateClearButton;
        UpdateClearButton();
    }

    private void UpdateClearButton() => ClearButton.IsEnabled = _list.HasFinished;

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is TransferRow row) _list.Cancel(row);
    }

    private void Clear_Click(object sender, RoutedEventArgs e) => _list.ClearFinished();
}
