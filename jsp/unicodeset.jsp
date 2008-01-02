<html>

<head>
<meta http-equiv="Content-Language" content="en-us">
<meta name="GENERATOR" content="Microsoft FrontPage 6.0">
<meta name="ProgId" content="FrontPage.Editor.Document">
<meta http-equiv="Content-Type" content="text/html; charset=utf-8">
<title>UnicodeSet Comparison Demo</title>
<%@ page contentType="text/html; charset=utf-8"%>
<%@ page import="com.ibm.icu.text.*" %> 
<%@ page import="com.ibm.icu.lang.*" %>
<%@ page import="com.ibm.icu.impl.*" %>
<%@ page import="java.util.regex.*" %>
<%@ page import="jsp.*" %>
<%@ page import="org.unicode.cldr.icu.*" %>
<style>
<link rel="stylesheet" type="text/css" href="index.css">
</style>
</head>
<body>
<%
		request.setCharacterEncoding("UTF-8"); 
		boolean abbreviate = request.getParameter("abb") != null;

		String setA = request.getParameter("a");
		if (setA == null) {
			setA = "[:Lowercase:]";
		}
		String setB = request.getParameter("b");
		if (setB == null) {
			setB = "[:Lowercase_Letter:]";
		}
		
		String[] abResults = new String[3];
		int[] abSizes = new int[3];
		UnicodeUtilities.getDifferences(setA, setB, abbreviate, abResults, abSizes);
		
		NumberFormat nf = NumberFormat.getIntegerInstance();
%>
<h1>UnicodeSet Demo (<a target="u" href="list-unicodeset.jsp">List</a>)</h1>
<form name="myform" action="http://unicode.org/cldr/utility/unicodeset.jsp" method="POST">
  <table border="1" cellpadding="0" cellspacing="0" style="border-collapse: collapse; width:100%">
    <tr>
      <th style="width: 50%">Input A</th>
      <th style="width: 50%">Input B</th>
    </tr>
    <tr>
      <td ><textarea name="a" rows="5" cols="10" style="width: 100%; height: 100%"><%=setA%></textarea></td>
      <td ><textarea name="b" rows="5" cols="10" style="width: 100%; height: 100%"><%=setB%></textarea></td>
    </tr>
    <tr>
      <td colspan="2"><input type="submit" value="Compare" />
      <input type="checkbox" <%=abbreviate ? "checked" : ""%> name="abb"><label for="abb">abbreviate</label></td>
    </tr>
    <tr>
      <th align="center" >Only in A: <%=nf.format(abSizes[0])%> Code Points</th>
      <th align="center" >Only in B: <%=nf.format(abSizes[1])%> Code Points</th>
    </tr>
    <tr>
      <td ><%=abResults[0]%>&nbsp;</td>
      <td ><%=abResults[1]%>&nbsp;</td>
    </tr>
    <tr>
      <th colspan="2">In both A and B: <%=nf.format(abSizes[2])%> Code Points</th>
    </tr>
    <tr>
      <td colspan="2"><%=abResults[2]%>&nbsp;</td>
    </tr>
  </table>
</form>
<p>Version 3, Built with ICU version: <%= com.ibm.icu.util.VersionInfo.ICU_VERSION.toString() %></p>

</body>

</html>
