<%--
 Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
--%>
<%@ taglib prefix="bugs" uri="http://tomcat.apache.org/bugs" %>
<%@ taglib prefix="tags" tagdir="/WEB-INF/tags" %>
<%--
  Tries to place the classic tag that defines a variable
  into a simple tag
--%>
<bugs:Bug48616b/>
<tags:bug42390>
    <bugs:Bug46816a>
        <bugs:Bug48616b/>
    </bugs:Bug46816a>
</tags:bug42390>
<%
    out.println(X);
%>
