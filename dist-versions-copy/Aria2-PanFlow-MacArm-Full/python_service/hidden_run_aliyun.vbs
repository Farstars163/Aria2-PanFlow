Set objShell = WScript.CreateObject("WScript.Shell")
If WScript.Arguments.Count >= 2 Then
  objShell.CurrentDirectory = WScript.Arguments(0)
  objShell.Run WScript.Arguments(1), 0, False
End If
